package com.gpmanager;
import lombok.*;
@AllArgsConstructor
enum Aj {
GENERIC(0),
PRODUCTION(1),
LOOT(2),
PK_LOOT(3),
MARKET(4),
PK_DEATH(5),
TRANSFER(6);
@Getter
final int priority;
}
