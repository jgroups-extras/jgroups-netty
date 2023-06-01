package org.jgroups.util;

import org.jgroups.Address;
import org.jgroups.Event;

public class WatermarkOverflowEvent extends Event {
   private final boolean wasOverflow;
   public WatermarkOverflowEvent(Address address, boolean wasOverflow) {
      super(Event.USER_DEFINED, address);
      this.wasOverflow = wasOverflow;
   }

   public Address address() {
      return arg();
   }

   public boolean wasOverFlow() {
      return wasOverflow;
   }
}
