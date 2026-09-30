package com.gpmanager;
import lombok.*;
/** Bounded inspectable-detail windows (section L). */
@AllArgsConstructor
public enum Db {
DAYS_30("30 days", 30),
DAYS_90("90 days", 90),
DAYS_180("180 days", 180),
DAYS_365("365 days", 365);
final String label;
@Getter
final int days;
public String toString() {
return label;
}
}
