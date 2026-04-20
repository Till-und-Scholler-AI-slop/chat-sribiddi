package com.rft.chat_sribiddi.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByProviderId(String providerId);

  /**
   * Acquires a row-level write lock on the user record. Used by
   * {@link com.rft.chat_sribiddi.billing.StripeService#ensureCustomer} to make Stripe Customer
   * creation race-safe: concurrent callers for the same user serialize on this lock and only the
   * first one creates a new Customer.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<User> findWithLockById(Long id);
}
