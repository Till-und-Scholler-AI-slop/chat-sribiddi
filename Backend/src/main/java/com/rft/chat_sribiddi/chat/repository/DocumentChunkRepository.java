package com.rft.chat_sribiddi.chat.repository;

import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

  @Query(
      value =
          """
          SELECT dc.id, dc.document_id, dc.chunk_index, dc.page_start, dc.page_end,
                 dc.text, dc.token_estimate
          FROM document_chunks dc
          JOIN chat_documents d ON d.id = dc.document_id
          WHERE d.chat_id = :chatId
            AND d.user_id = :userId
            AND d.status = 'READY'
            AND dc.search_vector @@ websearch_to_tsquery('simple', :query)
          ORDER BY ts_rank_cd(dc.search_vector, websearch_to_tsquery('simple', :query)) DESC,
                   dc.document_id ASC,
                   dc.chunk_index ASC
          """,
      nativeQuery = true)
  List<DocumentChunkEntity> search(
      @Param("chatId") UUID chatId,
      @Param("userId") String userId,
      @Param("query") String query,
      Pageable pageable);

  @Query(
      value =
          """
          SELECT dc.id, dc.document_id, dc.chunk_index, dc.page_start, dc.page_end,
                 dc.text, dc.token_estimate
          FROM document_chunks dc
          JOIN chat_documents d ON d.id = dc.document_id
          WHERE d.chat_id = :chatId
            AND d.user_id = :userId
            AND d.status = 'READY'
          ORDER BY dc.document_id ASC, dc.chunk_index ASC
          """,
      nativeQuery = true)
  List<DocumentChunkEntity> findInitialChunks(
      @Param("chatId") UUID chatId, @Param("userId") String userId, Pageable pageable);

  void deleteByDocumentId(Long documentId);
}
