package org.jgroups.util;

import java.util.Objects;

import org.jgroups.Address;
import org.jgroups.Event;

public class MemberAvailabilityEvent extends Event {
   private final Address member;
   private final boolean available;
   public MemberAvailabilityEvent(Address member, boolean available) {
      super(Event.USER_DEFINED);
      this.member = Objects.requireNonNull(member);
      this.available = available;
   }

   public Address getMember() {
      return member;
   }

   public boolean isAvailable() {
      return available;
   }
}
