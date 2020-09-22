package org.dpppt.backend.sdk.model.gaen;

public enum ReportType {
  UNKNOWN, // Never returned by the client API.
  CONFIRMED_TEST,
  CONFIRMED_CLINICAL_DIAGNOSIS,
  SELF_REPORT,
  RECURSIVE, // Reserved for future use.
  REVOKED, // Used to revoke a key, never returned by client API.
}
