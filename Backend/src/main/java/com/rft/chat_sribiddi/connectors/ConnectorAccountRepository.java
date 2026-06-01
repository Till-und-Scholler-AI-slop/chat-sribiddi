package com.rft.chat_sribiddi.connectors;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConnectorAccountRepository extends JpaRepository<ConnectorAccountEntity, Long> {
  Optional<ConnectorAccountEntity> findByUserIdAndProvider(String userId, ConnectorProvider provider);

  boolean existsByUserIdAndProviderAndStatus(
      String userId, ConnectorProvider provider, ConnectorStatus status);

  void deleteByUserIdAndProvider(String userId, ConnectorProvider provider);
}
