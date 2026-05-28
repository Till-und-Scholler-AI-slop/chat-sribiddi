package com.rft.chat_sribiddi.billing;

public interface QuotaReservation {
  void commit();

  void rollback();
}
