package com.rft.chat_sribiddi.keyring;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, Long> {

  Optional<ApiKeyEntity> findByUserIdAndProvider(String userId, Provider provider);

  void deleteByUserIdAndProvider(String userId, Provider provider);

  boolean existsByUserIdAndProvider(String userId, Provider provider);
}
