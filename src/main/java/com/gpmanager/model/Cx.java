package com.gpmanager;
import lombok.*;
@AllArgsConstructor
enum Cx {
AUTO("Auto"), GENERAL("General"), PK("PK"), MIXED("Mixed");
@Getter
String displayName;
public String toString() {
 return displayName;
}
}
