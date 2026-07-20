import type { ColumnType, Generated, Insertable, Selectable, Updateable } from "kysely";

export type Timestamp = ColumnType<Date, Date | string | undefined, Date | string>;
export type NullableTimestamp = ColumnType<
  Date | null,
  Date | string | null | undefined,
  Date | string | null
>;
export type NullableColumn<T> = ColumnType<T | null, T | null | undefined, T | null>;

export type Provider = "OPENAI" | "GOOGLE" | "CLAUDE" | "OPENROUTER" | "BRAVE_SEARCH";
export type Role = "USER" | "ASSISTANT" | "SYSTEM";
export type MessageStatus = "STREAMING" | "COMPLETE" | "INCOMPLETE" | "FAILED";
export type DocumentStatus = "PROCESSING" | "READY" | "FAILED";

export interface UsersTable {
  id: Generated<number>;
  email: string | null;
  provider_id: string;
  custom_instructions: string | null;
}

export interface ChatsTable {
  chat_id: ColumnType<string, string | undefined, string>;
  title: string | null;
  created_at: Timestamp | null;
  user_id: string;
}

export interface MessagesTable {
  id: Generated<number>;
  chat_id: string;
  message: string;
  context: string | null;
  role: Role | null;
  created_at: Timestamp | null;
  prompt_tokens: number | null;
  generation_tokens: number | null;
  total_tokens: number | null;
  status: ColumnType<MessageStatus, MessageStatus | undefined, MessageStatus>;
  error_code: NullableColumn<string>;
}

export interface ApiKeysTable {
  id: Generated<number>;
  user_id: string;
  provider: Provider;
  encrypted_key: string;
  key_version: number;
  created_at: Timestamp | null;
  updated_at: Timestamp | null;
}

export interface ChatToolsTable {
  id: Generated<number>;
  chat_id: string;
  tool_name: string;
  enabled: boolean;
  created_at: Timestamp;
}

export interface ToolCallLogsTable {
  id: Generated<number>;
  message_id: number;
  tool_name: string;
  input_payload: string;
  output_payload: string | null;
  started_at: Timestamp;
  duration_ms: number;
  context_excerpt: string | null;
  created_at: Timestamp;
  client_id: string | null;
  status: string;
  error_message: string | null;
  display_type: string | null;
}

export interface UsageEventsTable {
  id: Generated<number>;
  user_id: number;
  message_id: number | null;
  provider: Provider;
  model_id: string;
  prompt_tokens: number | null;
  completion_tokens: number | null;
  total_tokens: number | null;
  cost_micro_cents: number | null;
  credential_source: string | null;
  created_at: Timestamp;
}

export interface ChatDocumentsTable {
  id: Generated<number>;
  chat_id: string;
  user_id: string;
  filename: string;
  mime_type: string;
  byte_size: number;
  sha256: string;
  status: DocumentStatus;
  error_code: NullableColumn<string>;
  payload: NullableColumn<Buffer>;
  attempts: ColumnType<number, number | undefined, number>;
  next_attempt_at: Timestamp;
  claimed_at: NullableTimestamp;
  updated_at: Timestamp;
  created_at: Timestamp;
}

export interface DocumentChunksTable {
  id: Generated<number>;
  document_id: number;
  chunk_index: number;
  page_start: number | null;
  page_end: number | null;
  text: string;
  token_estimate: number;
}

export interface ToolCredentialsTable {
  id: Generated<number>;
  user_id: string;
  provider: string;
  encrypted_secret: string;
  key_version: number;
  created_at: Timestamp | null;
  updated_at: Timestamp | null;
}

export interface ProviderConnectionsTable {
  id: Generated<number>;
  user_id: string;
  provider_id: string;
  connection_profile_id: string;
  auth_method: string;
  status: string;
  display_name: string | null;
  provider_account_id: string | null;
  provider_account_label: string | null;
  scopes: string | null;
  encrypted_secret_json: string | null;
  token_expires_at: NullableTimestamp;
  key_version: number;
  default_connection: boolean;
  last_validated_at: NullableTimestamp;
  created_at: Timestamp | null;
  updated_at: Timestamp | null;
}

export interface Database {
  users: UsersTable;
  chats: ChatsTable;
  messages: MessagesTable;
  api_keys: ApiKeysTable;
  chat_tools: ChatToolsTable;
  tool_call_logs: ToolCallLogsTable;
  usage_events: UsageEventsTable;
  chat_documents: ChatDocumentsTable;
  document_chunks: DocumentChunksTable;
  tool_credentials: ToolCredentialsTable;
  provider_connections: ProviderConnectionsTable;
}

export type UserRow = Selectable<UsersTable>;
export type NewUser = Insertable<UsersTable>;
export type UserUpdate = Updateable<UsersTable>;
export type ChatRow = Selectable<ChatsTable>;
export type NewChat = Insertable<ChatsTable>;
export type ChatUpdate = Updateable<ChatsTable>;
export type MessageRow = Selectable<MessagesTable>;
export type NewMessage = Insertable<MessagesTable>;
export type MessageUpdate = Updateable<MessagesTable>;
export type ProviderConnectionRow = Selectable<ProviderConnectionsTable>;
export type ChatDocumentRow = Selectable<ChatDocumentsTable>;
