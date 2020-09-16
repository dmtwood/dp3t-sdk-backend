/*
 * Copyright (c) 2020 Ubique Innovation AG <https://www.ubique.ch>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * SPDX-License-Identifier: MPL-2.0
 */

package org.dpppt.backend.sdk.data.gaen;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.dpppt.backend.sdk.model.gaen.CountryShareConfiguration;
import org.dpppt.backend.sdk.model.gaen.GaenKey;
import org.dpppt.backend.sdk.utils.UTCInstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

public class JDBCGAENDataServiceImpl implements GAENDataService {

  private static final Logger logger = LoggerFactory.getLogger(JDBCGAENDataServiceImpl.class);

  private static final String PGSQL = "pgsql";
  private final String dbType;
  private final NamedParameterJdbcTemplate jt;
  private final Duration releaseBucketDuration;
  // Time skew means the duration for how long a key still is valid __after__ it
  // has expired (e.g 2h
  // for now
  // https://developer.apple.com/documentation/exposurenotification/setting_up_a_key_server?language=objc)
  private final Duration timeSkew;

  public JDBCGAENDataServiceImpl(
      String dbType, DataSource dataSource, Duration releaseBucketDuration, Duration timeSkew) {
    this.dbType = dbType;
    this.jt = new NamedParameterJdbcTemplate(dataSource);
    this.releaseBucketDuration = releaseBucketDuration;
    this.timeSkew = timeSkew;
  }

  @Override
  @Transactional(readOnly = false)
  public void upsertExposees(List<GaenKey> gaenKeys, UTCInstant now) {
    upsertExposeesDelayed(gaenKeys, null, now);
  }

  @Override
  public void upsertExposeesDelayed(
      List<GaenKey> gaenKeys, UTCInstant delayedReceivedAt, UTCInstant now) {

    String sql = null;
    if (dbType.equals(PGSQL)) {
      sql =
          "insert into t_gaen_exposed (key, rolling_start_number, rolling_period,"
              + " transmission_risk_level, received_at) values (:key, :rolling_start_number,"
              + " :rolling_period, :transmission_risk_level, :received_at) on conflict on"
              + " constraint gaen_exposed_key do nothing";
    } else {
      sql =
          "merge into t_gaen_exposed using (values(cast(:key as varchar(24)),"
              + " :rolling_start_number, :rolling_period, :transmission_risk_level, :received_at))"
              + " as vals(key, rolling_start_number, rolling_period, transmission_risk_level,"
              + " received_at) on t_gaen_exposed.key = vals.key when not matched then insert (key,"
              + " rolling_start_number, rolling_period, transmission_risk_level, received_at)"
              + " values (vals.key, vals.rolling_start_number, vals.rolling_period,"
              + " transmission_risk_level, vals.received_at)";
    }
    var parameterList = new ArrayList<MapSqlParameterSource>();
    // Calculate the `receivedAt` just at the end of the current releaseBucket.
    var receivedAt =
        delayedReceivedAt == null
            ? now.roundToNextBucket(releaseBucketDuration).minus(Duration.ofMillis(1))
            : delayedReceivedAt;
    for (var gaenKey : gaenKeys) {
      MapSqlParameterSource params = new MapSqlParameterSource();
      params.addValue("key", gaenKey.getKeyData());
      params.addValue("rolling_start_number", gaenKey.getRollingStartNumber());
      params.addValue("rolling_period", gaenKey.getRollingPeriod());
      params.addValue("transmission_risk_level", gaenKey.getTransmissionRiskLevel());
      params.addValue("received_at", receivedAt.getDate());

      parameterList.add(params);
    }
    jt.batchUpdate(sql, parameterList.toArray(new MapSqlParameterSource[0]));
  }

  @Override
  @Transactional(readOnly = true)
  public List<GaenKey> getSortedExposedForKeyDate(
      UTCInstant keyDate, UTCInstant publishedAfter, UTCInstant publishedUntil, UTCInstant now) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("rollingPeriodStartNumberStart", keyDate.get10MinutesSince1970());
    params.addValue("rollingPeriodStartNumberEnd", keyDate.plusDays(1).get10MinutesSince1970());
    params.addValue("publishedUntil", publishedUntil.getDate());

    String sql =
        "select pk_exposed_id, key, rolling_start_number, rolling_period, transmission_risk_level"
            + " from t_gaen_exposed where rolling_start_number >= :rollingPeriodStartNumberStart"
            + " and rolling_start_number < :rollingPeriodStartNumberEnd and received_at <"
            + " :publishedUntil";
    // we need to subtract the time skew since we want to release it iff
    // rolling_start_number +
    // rolling_period + timeSkew < NOW
    // note though that since we use `<` instead of `<=` a key which is valid until
    // 24:00 will be
    // accepted until 02:00 (by the clients, so we MUST NOT release it before
    // 02:00), but 02:00 lies
    // in the bucket of 04:00. So the key will be released
    // earliest 04:00.
    params.addValue(
        "maxAllowedStartNumber",
        now.roundToBucketStart(releaseBucketDuration).minus(timeSkew).get10MinutesSince1970());
    sql += " and rolling_start_number + rolling_period < :maxAllowedStartNumber";

    // note that received_at is always rounded to `next_bucket` - 1ms to difuse
    // actual upload time
    if (publishedAfter != null) {
      params.addValue("publishedAfter", publishedAfter.getDate());
      sql += " and received_at >= :publishedAfter";
    }

    sql += " order by pk_exposed_id desc";

    return jt.query(sql, params, new GaenKeyRowMapper());
  }

  @Override
  @Transactional(readOnly = false)
  public void cleanDB(Duration retentionPeriod) {
    var retentionTime = UTCInstant.now().minus(retentionPeriod);
    logger.info("Cleanup DB entries before: " + retentionTime);
    MapSqlParameterSource params =
        new MapSqlParameterSource("retention_time", retentionTime.getDate());
    String sqlExposed = "delete from t_gaen_exposed where received_at < :retention_time";
    jt.update(sqlExposed, params);
  }

  @Override
  public void upsertExposees(
      List<GaenKey> keys, List<CountryShareConfiguration> visitedCountries, UTCInstant now) {
    String sql = null;
    String visitedCountrySql = null;
    if (dbType.equals(PGSQL)) {
      sql =
          "insert into t_gaen_exposed (pk_exposed_id, key, rolling_start_number, rolling_period,"
              + " transmission_risk_level, origin, report_type, days_since_onset_of_symptoms,"
              + " received_at) values (:pk_exposed_id,:key, :rolling_start_number, :rolling_period,"
              + " :transmission_risk_level, :origin, :report_type, :days_since_onset_of_symptoms,"
              + " :received_at) on conflict on constraint gaen_exposed_key do nothing";

      visitedCountrySql =
          "insert into t_visited (pkf_exposed_id, country) values (:pkf_exposed_id, :country) on"
              + " conflict do nothing";
    } else {
      sql =
          "merge into t_gaen_exposed using (values(:pk_exposed_id, cast(:key as varchar(24)),"
              + " :rolling_start_number, :rolling_period, :transmission_risk_level, :origin,"
              + " :report_type, :days_since_onset_of_symptoms, :received_at)) as"
              + " vals(pk_exposed_id,key, rolling_start_number, rolling_period,"
              + " transmission_risk_level,origin, report_type, days_since_onset_of_symptoms,"
              + " received_at) on t_gaen_exposed.key = vals.key when not matched then insert"
              + " (pk_exposed_id,key, rolling_start_number, rolling_period,"
              + " transmission_risk_level,origin, report_type, days_since_onset_of_symptoms,"
              + " received_at) values (vals.pk_exposed_id,vals.key, vals.rolling_start_number,"
              + " vals.rolling_period, transmission_risk_level,vals.origin, vals.report_type,"
              + " vals.days_since_onset_of_symptoms, vals.received_at)";
      visitedCountrySql =
          "merge into t_visited using (values(:pkf_exposed_id, :country)) as vals(pkf_exposed_id,"
              + " country) on t_visited.pkf_exposed_id = vals.pkf_exposed_id when not matched then"
              + " insert (pkf_exposed_id, country) values (vals.pkf_exposed_id, vals.country)";
    }
    var parameterList = new ArrayList<MapSqlParameterSource>();
    var visitedCountriesList = new ArrayList<MapSqlParameterSource>();
    // Calculate the `receivedAt` just at the end of the current releaseBucket.
    var receivedAt = now.roundToNextBucket(releaseBucketDuration).minus(Duration.ofMillis(1));
    // reserve a block of ids for our insert
    var first_index = reserveSequenceBlock(keys.size());
    Integer index = first_index;
    for (var gaenKey : keys) {
      MapSqlParameterSource params = new MapSqlParameterSource();

      params.addValue("pk_exposed_id", index);
      for (var country : visitedCountries) {
        MapSqlParameterSource visitedCountryParams = new MapSqlParameterSource();
        visitedCountryParams.addValue("pkf_exposed_id", index);
        visitedCountryParams.addValue("country", country.getCountryCode());
        visitedCountriesList.add(visitedCountryParams);
      }
      // update the index with + 1
      index += 1;

      params.addValue("key", gaenKey.getKeyData());
      params.addValue("rolling_start_number", gaenKey.getRollingStartNumber());
      params.addValue("rolling_period", gaenKey.getRollingPeriod());
      params.addValue("transmission_risk_level", gaenKey.getTransmissionRiskLevel());
      params.addValue("origin", gaenKey.getOrigin());
      params.addValue("report_type", gaenKey.getReportType());
      params.addValue("days_since_onset_of_symptoms", gaenKey.getDaysSinceOnsetOfSymptoms());
      params.addValue("received_at", receivedAt.getDate());

      parameterList.add(params);
    }
    jt.batchUpdate(sql, parameterList.toArray(new MapSqlParameterSource[0]));
    jt.batchUpdate(visitedCountrySql, visitedCountriesList.toArray(new MapSqlParameterSource[0]));
  }

  @Transactional(readOnly = false)
  Integer reserveSequenceBlock(int numberOfElements) {
    String sql = "SELECT last_value from t_gaen_exposed_pk_exposed_id_seq";
    String updateSequence = "SELECT setval('t_gaen_exposed_pk_exposed_id_seq', :new_value)";
    var params = new MapSqlParameterSource();

    Integer firstValue = jt.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    Integer lastValue = firstValue + numberOfElements;
    params.addValue("new_value", lastValue);
    jt.update(updateSequence, params);

    return firstValue + 1;
  }

  @Override
  public List<GaenKey> getSortedExposedForKeyDate(
      UTCInstant keyDate,
      CountryShareConfiguration forCountry,
      UTCInstant publishedAfter,
      UTCInstant publishedUntil,
      UTCInstant now) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("rollingPeriodStartNumberStart", keyDate.get10MinutesSince1970());
    params.addValue("rollingPeriodStartNumberEnd", keyDate.plusDays(1).get10MinutesSince1970());
    params.addValue("publishedUntil", publishedUntil.getDate());
    params.addValue("country", forCountry.getCountryCode());

    String sql =
        "select pk_exposed_id, key, rolling_start_number, rolling_period, origin,"
            + " report_type, days_since_onset_of_symptoms from t_gaen_exposed keys where"
            + " rolling_start_number >= :rollingPeriodStartNumberStart and rolling_start_number <"
            + " :rollingPeriodStartNumberEnd and received_at < :publishedUntil left join t_visited"
            + " country on country.pfk_exposed_id = keys.pk_exposed_id where country.country ="
            + " :country";
    // we need to subtract the time skew since we want to release it iff
    // rolling_start_number +
    // rolling_period + timeSkew < NOW
    // note though that since we use `<` instead of `<=` a key which is valid until
    // 24:00 will be
    // accepted until 02:00 (by the clients, so we MUST NOT release it before
    // 02:00), but 02:00 lies
    // in the bucket of 04:00. So the key will be released
    // earliest 04:00.
    params.addValue(
        "maxAllowedStartNumber",
        now.roundToBucketStart(releaseBucketDuration).minus(timeSkew).get10MinutesSince1970());
    sql += " and rolling_start_number + rolling_period < :maxAllowedStartNumber";

    // note that received_at is always rounded to `next_bucket` - 1ms to difuse
    // actual upload time
    if (publishedAfter != null) {
      params.addValue("publishedAfter", publishedAfter.getDate());
      sql += " and received_at >= :publishedAfter";
    }

    sql += " order by pk_exposed_id desc";

    return jt.query(sql, params, new GaenKeyRowMapper());
  }
}
