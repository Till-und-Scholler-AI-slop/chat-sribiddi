package com.rft.chat_sribiddi.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import com.rft.chat_sribiddi.chat.repository.DocumentChunkRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DocumentRetrievalServiceTest {

  @Mock private DocumentChunkRepository chunkRepository;

  private DocumentRetrievalService service;
  private UUID chatId;

  @BeforeEach
  void setUp() {
    service = new DocumentRetrievalService(chunkRepository, new RagProperties());
    chatId = UUID.randomUUID();
  }

  @Test
  void retrieveDocuments_searchesByQueryAndMapsChunksToSpringAiDocuments() {
    DocumentChunkEntity chunk = chunk(42L, 1, 3, 4, "  Important text.  ");
    when(chunkRepository.search(eq(chatId), eq("user-1"), eq("deep question"), any()))
        .thenReturn(List.of(chunk));

    List<Document> documents = service.retrieveDocuments(chatId, "user-1", " deep question ");

    assertThat(documents).hasSize(1);
    Document document = documents.getFirst();
    assertThat(document.getText()).isEqualTo("Important text.");
    assertThat(document.getMetadata())
        .containsEntry("document_id", 42L)
        .containsEntry("chunk_index", 1)
        .containsEntry("page_start", 3)
        .containsEntry("page_end", 4);

    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(chunkRepository).search(eq(chatId), eq("user-1"), eq("deep question"), pageable.capture());
    assertThat(pageable.getValue().getPageSize()).isEqualTo(4);
  }

  @Test
  void retrieveDocuments_usesInitialChunksForGenericAttachmentPrompts() {
    DocumentChunkEntity chunk = chunk(42L, 0, null, null, "Attachment intro.");
    when(chunkRepository.findInitialChunks(eq(chatId), eq("user-1"), any()))
        .thenReturn(List.of(chunk));

    List<Document> documents =
        service.retrieveDocuments(chatId, "user-1", "Please analyze the attachment");

    assertThat(documents).singleElement().extracting(Document::getText).isEqualTo("Attachment intro.");
    verify(chunkRepository, never()).search(eq(chatId), eq("user-1"), any(), any());
  }

  @Test
  void retrieveDocuments_fallsBackToInitialChunksWhenSearchReturnsNoMatches() {
    when(chunkRepository.search(eq(chatId), eq("user-1"), eq("unknown"), any()))
        .thenReturn(List.of());
    when(chunkRepository.findInitialChunks(eq(chatId), eq("user-1"), any()))
        .thenReturn(List.of(chunk(42L, 0, null, null, "First chunk.")));

    List<Document> documents = service.retrieveDocuments(chatId, "user-1", "unknown");

    assertThat(documents).singleElement().extracting(Document::getText).isEqualTo("First chunk.");
  }

  @Test
  void retrieve_usesSpringAiQueryContextForChatScopedRetrieval() {
    when(chunkRepository.search(eq(chatId), eq("user-1"), eq("deep question"), any()))
        .thenReturn(List.of(chunk(42L, 0, null, null, "Context from query.")));

    Query query =
        Query.builder()
            .text("deep question")
            .context(
                Map.of(
                    DocumentRetrievalService.CHAT_ID_CONTEXT_KEY, chatId,
                    DocumentRetrievalService.USER_ID_CONTEXT_KEY, "user-1"))
            .build();

    List<Document> documents = service.retrieve(query);

    assertThat(documents).singleElement().extracting(Document::getText).isEqualTo("Context from query.");
  }

  @Test
  void retrieve_returnsEmptyWhenSpringAiQueryContextIsMissing() {
    assertThat(service.retrieve(new Query("deep question"))).isEmpty();
    verify(chunkRepository, never()).search(any(), any(), any(), any());
    verify(chunkRepository, never()).findInitialChunks(any(), any(), any());
  }

  private static DocumentChunkEntity chunk(
      Long documentId, Integer chunkIndex, Integer pageStart, Integer pageEnd, String text) {
    DocumentChunkEntity entity = new DocumentChunkEntity();
    entity.setDocumentId(documentId);
    entity.setChunkIndex(chunkIndex);
    entity.setPageStart(pageStart);
    entity.setPageEnd(pageEnd);
    entity.setText(text);
    entity.setTokenEstimate(10);
    return entity;
  }
}
