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
import java.util.List;
import org.dpppt.backend.sdk.model.gaen.CountryShareConfiguration;
import org.dpppt.backend.sdk.model.gaen.GaenKey;
import org.dpppt.backend.sdk.utils.UTCInstant;

public interface GAENDataService {

  /**
   * Upserts (Update or Inserts) the given list of exposed keys
   *
   * @param keys the list of exposed keys to upsert
   * @param visitedCountries the list of visited countries
   * @param now time of the request
   */
  void insertExposees(
      List<GaenKey> keys, List<CountryShareConfiguration> visitedCountries, UTCInstant now);

  /**
   * <b> DEPRECATED use upsertExposees(keys, visitedCountries, now) </b> <br>
   * Upserts (Update or Inserts) the given list of exposed keys
   *
   * @param keys the list of exposed keys to upsert
   * @param now time of the request
   */
  @Deprecated
  void insertExposees(List<GaenKey> keys, UTCInstant now);

  /**
   * Upserts (Update or Inserts) the given list of exposed keys, with delayed release of same day
   * TEKs
   *
   * @param keys the list of exposed keys to upsert
   * @param delayedReceivedAt the timestamp to use for the delayed release (if null use now rounded
   *     to next bucket)
   * @param now time of the request
   */
  @Deprecated
  void insertExposeesDelayed(List<GaenKey> keys, UTCInstant delayedReceivedAt, UTCInstant now);

  /**
   * <b> DEPRECATED use the method needing a country</b> <br>
   * Returns all exposeed keys for the given batch, where a batch is parametrized with keyDate (for
   * which day was the key used) publishedAfter/publishedUntil (when was the key published) and now
   * (has the key expired or not, based on rollingStartNumber and rollingPeriod).
   *
   * @param keyDate must be midnight UTC
   * @param publishedAfter when publication should start
   * @param publishedUntil last publication
   * @param now the start of the query
   * @return all exposeed keys for the given batch
   */
  @Deprecated
  List<GaenKey> getSortedExposedForKeyDate(
      UTCInstant keyDate, UTCInstant publishedAfter, UTCInstant publishedUntil, UTCInstant now);

  /**
   * Returns all exposeed keys for the given batch, where a batch is parametrized with keyDate (for
   * which day was the key used) publishedAfter/publishedUntil (when was the key published) and now
   * (has the key expired or not, based on rollingStartNumber and rollingPeriod).
   *
   * @param since return keys published after this instant
   * @param forCountries return keys for the specified countries
   * @param now the start of the query
   * @return all exposeed keys for the given batch
   */
  List<GaenKey> getSortedExposedSince(UTCInstant since, List<String> forCountries, UTCInstant now);

  /**
   * deletes entries older than retentionperiod
   *
   * @param retentionPeriod in milliseconds
   */
  void cleanDB(Duration retentionPeriod);
}
