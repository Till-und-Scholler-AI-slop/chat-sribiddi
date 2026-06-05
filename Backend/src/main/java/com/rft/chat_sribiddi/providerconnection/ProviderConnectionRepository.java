package com.rft.chat_sribiddi.providerconnection;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderConnectionRepository extends JpaRepository<ProviderConnectionEntity, Long> {

  List<ProviderConnectionEntity> findByUserIdOrderByProviderIdAscCreatedAtAsc(String userId);

  List<ProviderConnectionEntity> findByUserIdAndProviderIdOrderByCreatedAtAsc(
      String userId, String providerId);

  Optional<ProviderConnectionEntity> findByIdAndUserId(Long id, String userId);

  Optional<ProviderConnectionEntity> findFirstByUserIdAndProviderIdAndAuthMethodOrderByCreatedAtAsc(
      String userId, String providerId, ProviderAuthMethod authMethod);

  Optional<ProviderConnectionEntity>
      findFirstByUserIdAndProviderIdAndAuthMethodInAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
          String userId,
          String providerId,
          Collection<ProviderAuthMethod> authMethods,
          ProviderConnectionStatus status);

  Optional<ProviderConnectionEntity>
      findFirstByUserIdAndProviderIdAndStatusOrderByDefaultConnectionDescCreatedAtAsc(
          String userId, String providerId, ProviderConnectionStatus status);

  boolean existsByUserIdAndProviderIdAndStatus(
      String userId, String providerId, ProviderConnectionStatus status);

  void deleteByUserId(String userId);

  void deleteByUserIdAndProviderIdAndAuthMethod(
      String userId, String providerId, ProviderAuthMethod authMethod);
}
