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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class DocumentRetrievalServiceTest {

  @Mock private DocumentChunkRepository chunkRepository;

  private DocumentRetrievalService service;
  private UUID chatId;

  @BeforeEach
  void setUp() {
    service = new DocumentRetrievalService(chunkRepository);
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
  void retrieveContext_keepsLegacyFormattedContext() {
    when(chunkRepository.findInitialChunks(eq(chatId), eq("user-1"), any()))
        .thenReturn(List.of(chunk(42L, 0, 2, 2, "First chunk.")));

    String context = service.retrieveContext(chatId, "user-1", "");

    assertThat(context)
        .contains("Relevant document excerpts for this chat:")
        .contains("[document 42, chunk 1, pages 2]")
        .contains("First chunk.");
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
