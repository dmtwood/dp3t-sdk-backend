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
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.annotation.Transactional;

public class JDBCGAENDataServiceImpl implements GAENDataService {

  private static final Logger logger = LoggerFactory.getLogger(JDBCGAENDataServiceImpl.class);

  private static final String PGSQL = "pgsql";
  private final String dbType;
  private final NamedParameterJdbcTemplate jt;
  private final Duration releaseBucketDuration;
  private final String originCountry;
  private final SimpleJdbcInsert insertGaenExposed;
  private final SimpleJdbcInsert insertVisited;

  // Time skew means the duration for how long a key still is valid __after__ it
  // has expired (e.g 2h
  // for now
  // https://developer.apple.com/documentation/exposurenotification/setting_up_a_key_server?language=objc)
  private final Duration timeSkew;

  public JDBCGAENDataServiceImpl(
      String dbType,
      DataSource dataSource,
      Duration releaseBucketDuration,
      Duration timeSkew,
      String originCountry) {
    this.dbType = dbType;
    this.jt = new NamedParameterJdbcTemplate(dataSource);
    this.releaseBucketDuration = releaseBucketDuration;
    this.timeSkew = timeSkew;
    this.originCountry = originCountry;
    this.insertGaenExposed = new SimpleJdbcInsert(dataSource).withTableName("t_gaen_exposed");
    this.insertGaenExposed.setGeneratedKeyName("pk_exposed_id");
    this.insertVisited = new SimpleJdbcInsert(dataSource).withTableName("t_visited");
    this.insertVisited.setGeneratedKeyName("transmitted");
  }

  @Override
  @Transactional(readOnly = false)
  public void insertExposees(List<GaenKey> gaenKeys, UTCInstant now) {
    insertExposeesDelayed(gaenKeys, null, now);
  }

  @Override
  @Transactional(readOnly = false)
  public void insertExposeesDelayed(
      List<GaenKey> gaenKeys, UTCInstant delayedReceivedAt, UTCInstant now) {
    List<CountryShareConfiguration> visitedCountries =
        List.of(new CountryShareConfiguration(originCountry, 1));
    insertExposees(gaenKeys, visitedCountries, now);
  }

  @Override
  public void insertExposees(
      List<GaenKey> keys, List<CountryShareConfiguration> visitedCountries, UTCInstant now) {
    // Calculate the `receivedAt` just at the end of the current releaseBucket.
    var receivedAt = now.roundToNextBucket(releaseBucketDuration).minus(Duration.ofMillis(1));
    List<MapSqlParameterSource> visitedBatchInsert = new ArrayList<>();
    for (GaenKey k : keys) {
      MapSqlParameterSource keyParams = createInsertParamsForKey(k, receivedAt);
      int keyId = insertGaenExposed.executeAndReturnKey(keyParams).intValue();
      for (CountryShareConfiguration country : visitedCountries) {
        if (country.getShareKeyWithCountry() == 1) {
          visitedBatchInsert.add(createInsertParamsForVisited(keyId, country.getCountryCode()));
        }
      }
    }
    if (!visitedBatchInsert.isEmpty()) {
      insertVisited.executeBatch(
          visitedBatchInsert.toArray(new SqlParameterSource[visitedBatchInsert.size()]));
    }
  }

  @Override
  @Transactional(readOnly = true)
  public List<GaenKey> getSortedExposedForKeyDate(
      UTCInstant keyDate, UTCInstant publishedAfter, UTCInstant publishedUntil, UTCInstant now) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("rollingPeriodStartNumberStart", keyDate.get10MinutesSince1970());
    params.addValue("rollingPeriodStartNumberEnd", keyDate.plusDays(1).get10MinutesSince1970());
    params.addValue("publishedUntil", publishedUntil.getDate());
    // for v1 we don't have different countries, so we only want keys which were used n
    // origin_country
    params.addValue("origin_country", originCountry);

    String sql =
        "select pk_exposed_id, key, rolling_start_number, rolling_period"
            + " from t_gaen_exposed key"
            + " inner join t_visited country on country.pfk_exposed_id = key.pk_exposed_id "
            + " where key.rolling_start_number >= :rollingPeriodStartNumberStart"
            + " and key.rolling_start_number < :rollingPeriodStartNumberEnd and key.received_at <"
            + " :publishedUntil"
            + " and country.country = :origin_country";
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
    sql += " and key.rolling_start_number + key.rolling_period < :maxAllowedStartNumber";

    // note that received_at is always rounded to `next_bucket` - 1ms to difuse
    // actual upload time
    if (publishedAfter != null) {
      params.addValue("publishedAfter", publishedAfter.getDate());
      sql += " and key.received_at >= :publishedAfter";
    }

    sql += " order by key.pk_exposed_id desc";

    return jt.query(sql, params, new GaenKeyRowMapper());
  }

  @Override
  public List<GaenKey> getSortedExposedSince(
      UTCInstant since, List<String> forCountries, UTCInstant now) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("since", since.getDate());
    params.addValue("countries", forCountries);
    params.addValue("maxBucket", now.roundToBucketStart(releaseBucketDuration));
    params.addValue("timeSkewMillis", timeSkew.toMillis());

    // Select keys for countries since the given date. We need to make sure, only keys are returned
    // that are allowed to be published.
    // For this, we calculate the expiry for each key in a sub query. The expiry is then used for
    // the where clause:
    // - if expiry <= received_at: the key was ready to publish when we received it. Release this
    // key, if received_at in [since, maxBucket)
    // - if expiry > received_at: we have to wait until expiry till we can release this key. This
    // means we only release the key if expiry in [since, maxBucket)
    // This problem arises, because we only want key with received_at after since, but we need to
    // ensure that we relase ALL keys meaning keys which were still valid when they were received

    // we need to add the time skew to calculate the expiry timestamp of a key:
    // TO_TIMESTAMP((rolling_start_number + rolling_period) * 10 * 60 * 1000 + :timeSkewMillis

    String sql =
        "select distinct pk_exposed_id, key, rolling_start_number, rolling_period"
            + " from (select *, TO_TIMESTAMP((rolling_start_number +"
            + " rolling_period) * 10 * 60 * 1000 + :timeSkewMillis) as expiry from t_gaen_exposed)"
            + " key inner join t_visited country on country.pfk_exposed_id = key.pk_exposed_id"
            + " where country.country in (:countries) AND ((key.received_at >= :since AND"
            + " key.received_at < :maxBucket AND key.expiry <= key.received_at) OR (key.expiry"
            + " >= :since AND key.expiry < :maxBucket AND key.expiry > key.received_at))";

    sql += " order key.by pk_exposed_id desc";

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

  private MapSqlParameterSource createInsertParamsForVisited(int keyId, String countryCode) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("pfk_exposed_id", keyId);
    params.addValue("country", countryCode);
    return params;
  }

  private MapSqlParameterSource createInsertParamsForKey(GaenKey k, UTCInstant receivedAt) {
    MapSqlParameterSource params = new MapSqlParameterSource();
    params.addValue("key", k.getKeyData());
    params.addValue("rolling_start_number", k.getRollingStartNumber());
    params.addValue("rolling_period", k.getRollingPeriod());
    params.addValue("origin", this.originCountry);
    params.addValue("report_type", null);
    params.addValue("days_since_onset_of_symptoms", null);
    params.addValue("received_at", receivedAt.getDate());
    return params;
  }
}
