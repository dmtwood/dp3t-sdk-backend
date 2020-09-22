package org.dpppt.backend.sdk.data.gaen;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.dpppt.backend.sdk.model.gaen.GaenKey;
import org.dpppt.backend.sdk.model.gaen.ReportType;
import org.springframework.jdbc.core.RowMapper;

public class GaenKeyRowMapper implements RowMapper<GaenKey> {

  @Override
  public GaenKey mapRow(ResultSet rs, int rowNum) throws SQLException {
    var gaenKey = new GaenKey();
    gaenKey.setKeyData(rs.getString("key"));
    gaenKey.setRollingStartNumber(rs.getInt("rolling_start_number"));
    gaenKey.setRollingPeriod(rs.getInt("rolling_period"));
    gaenKey.setTransmissionRiskLevel(0);

    String reportType = rs.getString("report_type");
    if (reportType != null) {
      gaenKey.setReportType(ReportType.valueOf(reportType));
    }

    int daysSinceOnsetOfSymptoms = rs.getInt("days_since_onset_of_symptoms");
    if (!rs.wasNull()) {
      gaenKey.setDaysSinceOnsetOfSymptoms(daysSinceOnsetOfSymptoms);
    }

    return gaenKey;
  }
}
