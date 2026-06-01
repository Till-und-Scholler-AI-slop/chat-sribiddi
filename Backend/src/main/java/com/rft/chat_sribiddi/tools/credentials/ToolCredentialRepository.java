package com.rft.chat_sribiddi.tools.credentials;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ToolCredentialRepository extends JpaRepository<ToolCredentialEntity, Long> {
  Optional<ToolCredentialEntity> findByUserIdAndProvider(String userId, ToolCredentialProvider provider);

  boolean existsByUserIdAndProvider(String userId, ToolCredentialProvider provider);

  void deleteByUserIdAndProvider(String userId, ToolCredentialProvider provider);
}
