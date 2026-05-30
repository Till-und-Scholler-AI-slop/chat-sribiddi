package com.rft.chat_sribiddi.chat.service;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public record OffsetPageRequest(int limit, long offset) implements Pageable {

  public static OffsetPageRequest of(int limit, Integer offset) {
    int sanitizedLimit = Math.max(1, limit);
    long sanitizedOffset = offset != null && offset > 0 ? offset.longValue() : 0L;
    return new OffsetPageRequest(sanitizedLimit, sanitizedOffset);
  }

  @Override
  public int getPageNumber() {
    return Math.toIntExact(offset / limit);
  }

  @Override
  public int getPageSize() {
    return limit;
  }

  @Override
  public long getOffset() {
    return offset;
  }

  @Override
  public Sort getSort() {
    return Sort.unsorted();
  }

  @Override
  public Pageable next() {
    return new OffsetPageRequest(limit, offset + limit);
  }

  @Override
  public Pageable previousOrFirst() {
    return hasPrevious() ? new OffsetPageRequest(limit, Math.max(0, offset - limit)) : first();
  }

  @Override
  public Pageable first() {
    return new OffsetPageRequest(limit, 0);
  }

  @Override
  public Pageable withPage(int pageNumber) {
    if (pageNumber < 0) {
      throw new IllegalArgumentException("pageNumber must not be negative");
    }
    return new OffsetPageRequest(limit, (long) pageNumber * limit);
  }

  @Override
  public boolean hasPrevious() {
    return offset > 0;
  }
}
