package com.rft.chat_sribiddi.chat.service;

import com.rft.chat_sribiddi.chat.dto.ChatDocumentDTO;
import com.rft.chat_sribiddi.chat.model.ChatDocumentEntity;
import com.rft.chat_sribiddi.chat.model.ChatDocumentEntity.DocumentStatus;
import com.rft.chat_sribiddi.chat.model.DocumentChunkEntity;
import com.rft.chat_sribiddi.chat.repository.ChatDocumentRepository;
import com.rft.chat_sribiddi.chat.repository.ChatRepository;
import com.rft.chat_sribiddi.chat.repository.DocumentChunkRepository;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ChatDocumentService {

  private final ChatRepository chatRepository;
  private final ChatDocumentRepository documentRepository;
  private final DocumentChunkRepository chunkRepository;
  private final DocumentTextExtractor textExtractor;
  private final DocumentChunker chunker;
  private final ChatProperties chatProperties;

  @Transactional
  public ChatDocumentDTO upload(UUID chatId, String userId, MultipartFile file) {
    requireChat(chatId, userId);
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document file is required");
    }
    long maxBytes = chatProperties.getMaxMediaAttachmentBytes();
    if (file.getSize() > maxBytes) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Document exceeds %d bytes".formatted(maxBytes));
    }

    String filename = safeFilename(file.getOriginalFilename());
    String mimeType = resolveMimeType(file.getContentType(), filename);
    if (!textExtractor.supports(mimeType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported document type");
    }

    byte[] data;
    try {
      data = file.getBytes();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document could not be read", e);
    }

    List<DocumentChunker.DocumentChunk> chunks = chunker.chunk(textExtractor.extract(mimeType, data));
    if (chunks.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document contains no extractable text");
    }

    ChatDocumentEntity document = new ChatDocumentEntity();
    document.setChatId(chatId);
    document.setUserId(userId);
    document.setFilename(filename);
    document.setMimeType(mimeType);
    document.setByteSize((long) data.length);
    document.setSha256(sha256(data));
    document.setStatus(DocumentStatus.READY);
    ChatDocumentEntity saved = documentRepository.save(document);

    chunkRepository.saveAll(
        chunks.stream()
            .map(chunk -> toEntity(saved.getId(), chunk))
            .toList());

    return ChatDocumentDTO.from(saved);
  }

  @Transactional(readOnly = true)
  public List<ChatDocumentDTO> list(UUID chatId, String userId) {
    requireChat(chatId, userId);
    return documentRepository.findByChatIdAndUserIdOrderByCreatedAtDesc(chatId, userId).stream()
        .map(ChatDocumentDTO::from)
        .toList();
  }

  @Transactional
  public void delete(UUID chatId, String userId, Long documentId) {
    requireChat(chatId, userId);
    ChatDocumentEntity document =
        documentRepository
            .findByIdAndChatIdAndUserId(documentId, chatId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    documentRepository.delete(document);
  }

  private void requireChat(UUID chatId, String userId) {
    chatRepository
        .findByChatIdAndUserId(chatId, userId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found"));
  }

  private static DocumentChunkEntity toEntity(Long documentId, DocumentChunker.DocumentChunk chunk) {
    DocumentChunkEntity entity = new DocumentChunkEntity();
    entity.setDocumentId(documentId);
    entity.setChunkIndex(chunk.index());
    entity.setPageStart(chunk.pageStart());
    entity.setPageEnd(chunk.pageEnd());
    entity.setText(chunk.text());
    entity.setTokenEstimate(chunk.tokenEstimate());
    return entity;
  }

  private static String safeFilename(String filename) {
    if (filename == null || filename.isBlank()) {
      return "document";
    }
    return filename.replaceAll("[\\\\/]+", "_").trim();
  }

  private static String resolveMimeType(String contentType, String filename) {
    if (contentType != null && !contentType.isBlank()) {
      return contentType;
    }
    String lower = filename == null ? "" : filename.toLowerCase();
    if (lower.endsWith(".pdf")) return "application/pdf";
    if (lower.endsWith(".json")) return "application/json";
    if (lower.endsWith(".xml")) return "application/xml";
    if (lower.endsWith(".ndjson")) return "application/x-ndjson";
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return "application/yaml";
    if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
    if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".csv")) return "text/plain";
    return "application/octet-stream";
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
