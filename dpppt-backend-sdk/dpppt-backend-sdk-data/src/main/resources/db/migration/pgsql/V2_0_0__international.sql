/*
 * Created by Ubique Innovation AG
 * https://www.ubique.ch
 * Copyright (c) 2020. All rights reserved.
 */

alter table t_gaen_exposed drop column transmission_risk_level;

alter table t_gaen_exposed add column origin varchar(10);
alter table t_gaen_exposed add column report_type varchar(30);
alter table t_gaen_exposed add column days_since_onset_of_symptoms integer;

CREATE TABLE t_visited
(
 pfk_exposed_id integer NOT NULL,
 country        varchar(10) NOT NULL,
 transmitted    boolean NOT NULL DEFAULT false,
 transmitted_at timestamp with time zone NULL,
 CONSTRAINT PK_t_visited PRIMARY KEY ( pfk_exposed_id, country ),
 CONSTRAINT r_gaen_exposed_visited FOREIGN KEY ( pfk_exposed_id ) REFERENCES t_gaen_exposed ( pk_exposed_id )
);

CREATE INDEX idx_visited_exposed_id ON t_visited
(
 pfk_exposed_id
);