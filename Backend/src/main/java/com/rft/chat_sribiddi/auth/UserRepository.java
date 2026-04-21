package com.rft.chat_sribiddi.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
  Optional<User> findByProviderId(String providerId);

  /**
   * Looks up a user by their Stripe Customer ID. Used by the billing webhook processor to map
   * subscription / invoice events (which reference the Stripe customer) back to the internal
   * user record.
   */
  Optional<User> findByStripeCustomerId(String stripeCustomerId);

  /**
   * Returns users whose {@code planStatus} matches the given value and whose
   * {@code planValidUntil} is strictly before {@code cutoff}. Used by
   * {@link com.rft.chat_sribiddi.billing.PlanLifecycleJob} to find users whose payment-failed
   * grace period has expired so they can be downgraded to {@link Plan#FREE}.
   */
  List<User> findByPlanStatusAndPlanValidUntilBefore(String planStatus, Instant cutoff);

  /**
   * Conditionally downgrades a user to {@link Plan#FREE} with the given {@code newPlanStatus}, but
   * only when the row still has {@code plan != FREE} and its {@code planValidUntil} is still
   * {@code < cutoff} at UPDATE time. This re-check protects against lost updates if
   * {@link com.rft.chat_sribiddi.billing.StripeWebhookProcessor} concurrently renewed the user's
   * subscription. Returns the number of rows updated (0 when the row was changed between the
   * initial read and this UPDATE).
   */
  @Modifying
  @Query(
      "UPDATE User u SET u.plan = com.rft.chat_sribiddi.auth.Plan.FREE, u.planStatus = :newPlanStatus "
          + "WHERE u.id = :id AND u.plan <> com.rft.chat_sribiddi.auth.Plan.FREE "
          + "AND u.planValidUntil < :cutoff")
  int downgradeToFreeIfExpired(
      @Param("id") Long id,
      @Param("newPlanStatus") String newPlanStatus,
      @Param("cutoff") Instant cutoff);

  /**
   * Bulk-downgrades users whose {@code planStatus = :expectedStatus} and
   * {@code planValidUntil < :cutoff} to {@link Plan#FREE} with the given {@code newPlanStatus}.
   * The WHERE clause re-checks state at UPDATE time, so a concurrent webhook that clears the
   * payment-failed flag will cause the row to be skipped rather than overwritten. Returns the
   * number of rows updated.
   */
  @Modifying
  @Query(
      "UPDATE User u SET u.plan = com.rft.chat_sribiddi.auth.Plan.FREE, u.planStatus = :newPlanStatus "
          + "WHERE u.planStatus = :expectedStatus AND u.planValidUntil < :cutoff")
  int downgradeAllByStatusAndValidUntilBefore(
      @Param("expectedStatus") String expectedStatus,
      @Param("newPlanStatus") String newPlanStatus,
      @Param("cutoff") Instant cutoff);



  /**
   * Acquires a row-level write lock on the user record. Used by
   * {@link com.rft.chat_sribiddi.billing.StripeService#ensureCustomer} to make Stripe Customer
   * creation race-safe: concurrent callers for the same user serialize on this lock and only the
   * first one creates a new Customer.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<User> findWithLockById(Long id);
}
