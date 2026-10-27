package com.gpmanager;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
public class QuantityFilterTest {
 @Test public void nullEntriesPositiveZerosNegativeAndLongLimitRemainExact(){
  var input=new LinkedHashMap<Integer,Long>();input.put(null,20L);input.put(-1,8L);input.put(1,null);input.put(2,0L);input.put(3,-2L);input.put(4,Long.MAX_VALUE);
  assertEquals(Map.of(-1,8L,4,Long.MAX_VALUE),FlowFilters.positive(input));
  assertEquals(Map.of(4,Long.MAX_VALUE),RaidHistory.positive(input));
  assertEquals(Map.of(-1,8L),FlowFilters.withoutItems(input,id->id==4));
  assertEquals(6,input.size());
 }
 @Test public void freshMutabilityAndPredicateCallOrderStayUnchanged(){
  var empty=FlowFilters.positive(null);empty.put(7,2L);assertTrue(FlowFilters.positive(null).isEmpty());
  var ordered=new LinkedHashMap<Integer,Long>();ordered.put(5,3L);ordered.put(1,2L);ordered.put(4,0L);
  var visited=new ArrayList<Integer>();assertEquals(Map.of(5,3L),FlowFilters.withoutItems(ordered,id->{visited.add(id);return id==1;}));assertEquals(List.of(5,1),visited);
  var detached=FlowFilters.positive(ordered);detached.clear();assertEquals(3,ordered.size());
  try {FlowFilters.withoutItems(null,id->true).put(1,1L);fail("empty withoutItems stays immutable");}catch(UnsupportedOperationException expected){}
 }
}
