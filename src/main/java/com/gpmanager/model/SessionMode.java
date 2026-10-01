package com.gpmanager;
import lombok.*;
@AllArgsConstructor
enum SessionMode {
AUTO("Auto"), GENERAL("General"), PK("PK"), MIXED("Mixed");
@Getter
final String displayName;
public String toString() {
 return displayName;
}
}
