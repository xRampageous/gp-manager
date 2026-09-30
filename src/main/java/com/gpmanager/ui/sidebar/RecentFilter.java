package com.gpmanager;
/**
* Presentation-only item eligibility for the Live Recent rows. The panel binds the current
* {@code LootPresentationFilterService} decision behind this seam so the kit stays free of
* plugin dependencies and the existing filter authority stays the only policy.
*/
@FunctionalInterface
interface RecentFilter {
/** @return true when presentation may show a row carrying this flow */
boolean isFlowIncluded(Ab flow);
default boolean showRecent(String name, long quantity) { return true; }
default void hideRecent(String name) { }
/** Changes whenever the decisions may change, so the Live list is rebuilt only then. */
default String version() {
return "";
}
}
