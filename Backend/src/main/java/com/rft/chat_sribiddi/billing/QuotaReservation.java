package com.rft.chat_sribiddi.billing;

public interface QuotaReservation extends AutoCloseable {
  QuotaReservation NOOP =
      new QuotaReservation() {
        @Override
        public void commit() {}

        @Override
        public void rollback() {}
      };

  static QuotaReservation noop() {
    return NOOP;
  }

  void commit();

  void rollback();

  @Override
  default void close() {
    rollback();
  }
}
