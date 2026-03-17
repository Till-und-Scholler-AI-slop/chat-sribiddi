#!/usr/bin/env bash
# Creates UX improvement GitHub issues for the chat-sribiddi project.
# Idempotent: skips any issue whose title already exists.
# Required env vars: GH_TOKEN, REPO (e.g. "owner/repo")
set -euo pipefail

# ── helpers ──────────────────────────────────────────────────────────────────

ensure_label() {
  gh label create "$1" --repo "$REPO" --color "$2" --description "$3" 2>/dev/null \
    || gh label edit "$1" --repo "$REPO" --color "$2" --description "$3" 2>/dev/null \
    || true
}

create_issue_if_missing() {
  local title="$1"
  local body_file="$2"
  local labels="$3"
  local existing
  existing=$(gh issue list --repo "$REPO" \
    --search "$title in:title" --state all \
    --json title --jq ".[] | select(.title == \"$title\") | .title" 2>/dev/null \
    | head -1 || true)
  if [ -n "$existing" ]; then
    echo "Already exists, skipping: $title"
  else
    gh issue create --repo "$REPO" \
      --title "$title" \
      --body-file "$body_file" \
      --label "$labels"
    echo "Created: $title"
  fi
}

# ── labels ───────────────────────────────────────────────────────────────────

ensure_label "ux"                   "e4e669" "User experience improvement"
ensure_label "enhancement"          "a2eeef" "New feature or request"
ensure_label "feature"              "0075ca" "New functionality"
ensure_label "developer-experience" "d4c5f9" "Improves developer workflow"

# ── Issue 1: Auto-title chats ─────────────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user, I want new chats to be automatically given a descriptive title based on my first message, so I can find past conversations in the sidebar without having to rename every single chat manually.

## Problem
Every new chat is titled `Chat N` (a sequential number). After a few days the sidebar becomes a list of indistinguishable entries ("Chat 1", "Chat 2", …), making it impossible to quickly navigate to a specific past conversation.

## Proposed Solution
After the first AI response completes in a newly created chat (title still at the default), trigger a lightweight title-generation call that asks the model to summarise the conversation in ≤8 words and use the result as the chat title.

## Acceptance Criteria
- [ ] When the first user message is sent and the chat still has the default generated title, the system triggers auto-title generation.
- [ ] The generated title is concise (max ~60 characters / ~8 words).
- [ ] The title is updated in the sidebar within a second of the first AI response completing.
- [ ] If auto-title generation fails silently, the chat keeps its default title — no error shown to the user.
- [ ] Users can still rename chats manually at any time (existing functionality preserved).
- [ ] A new backend endpoint `POST /chats/{chatId}/auto-title` (or equivalent) handles the AI call and PATCH to the title.

## Technical Notes
- **Backend**: Add `POST /chats/{chatId}/auto-title` in `ChatController`. `ChatService` calls the selected provider with a prompt like `"Summarise this conversation in 6 words or fewer for use as a chat title: ..."`.
- **Frontend**: In `useChatApi.ts` / `useChatStore.ts`, after `streamMessage` resolves and `conversation.title` is still the default, call the new endpoint and update the store.
- No database schema changes needed — title is already a `VARCHAR` column.

## Effort Estimate
Medium (Backend endpoint + frontend trigger logic)
EOF

create_issue_if_missing \
  "Auto-title new chats from the first message content" \
  /tmp/ux_issue_body.md \
  "enhancement,ux"

# ── Issue 2: Sidebar chat search ──────────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user with many conversations, I want to search through my chat list by title so I can instantly jump to the conversation I need instead of scrolling through dozens of entries.

## Problem
The sidebar currently shows all chats sorted by creation date (newest first) with no way to filter or search. Once a user has more than ~15 chats the list becomes unwieldy. Browser search (Ctrl+F) doesn't help because only the visible viewport is matched.

## Proposed Solution
Add a search input at the top of the sidebar (below the "Chats" heading and "New" button). As the user types, the list filters in real time to show only chats whose title contains the query string (case-insensitive).

## Acceptance Criteria
- [ ] A text input labelled "Search chats…" appears above the chat list in the sidebar.
- [ ] Typing filters the visible chats in real time (case-insensitive, substring match on title).
- [ ] Clearing the input restores the full list.
- [ ] When no chats match, an empty-state message is shown (e.g., "No chats found").
- [ ] The search query is local/ephemeral — not persisted between sessions.
- [ ] The active/selected chat item remains highlighted even when filtered.
- [ ] Works with keyboard navigation (Tab to focus, Escape to clear).

## Technical Notes
- **Frontend only** — filtering can be done client-side on the already-loaded `conversations` array in `useChatStore`.
- Add a `searchQuery` reactive ref inside `ChatSidebar.vue` and a computed `filteredConversations`.
- No backend changes required for the initial implementation (the existing `GET /chats?limit=50` loads all recent chats).
- Future: if the user has >50 chats, add a `search` query param to `GET /chats` on the backend.

## Effort Estimate
Small (Frontend only — computed filter added to the sidebar component)
EOF

create_issue_if_missing \
  "Add search/filter to the chat sidebar" \
  /tmp/ux_issue_body.md \
  "enhancement,ux"

# ── Issue 3: Copy button on code blocks ───────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a developer, I want a one-click "Copy" button on every code block in chat messages so I can quickly grab code without manually selecting all the text.

## Problem
`MarkdownMessage.vue` renders code blocks via `markdown-it` with no copy affordance. Copying code requires triple-click to select, then right-click → Copy — error-prone for multi-line snippets and a common frustration in developer-facing chat UIs.

## Proposed Solution
Inject a copy button into the top-right corner of every rendered code block. Clicking it:
1. Copies the block's text content to the clipboard.
2. Changes the icon to a checkmark for 2 seconds to confirm success.
3. Reverts to the copy icon automatically.

## Acceptance Criteria
- [ ] All code blocks (fenced and indented) show a copy icon button in the top-right corner.
- [ ] Clicking copies the raw code text (no extra whitespace artefacts).
- [ ] Button shows a success state (checkmark or "Copied!") for ~2 seconds, then reverts.
- [ ] Button is visible on hover (or always visible on touch/mobile devices).
- [ ] The detected language label (e.g., `ts`, `bash`) is shown next to the button when available.
- [ ] Works for both user messages and assistant messages.
- [ ] Does not break any existing markdown rendering.

## Technical Notes
- **Frontend only** — override the default `fence` renderer in `markdown-it` inside `MarkdownMessage.vue` to wrap each code block in a `<div class="code-block-wrapper">` containing a copy button.
- Use `onMounted` / `onUpdated` with a DOM walk to attach click handlers, since the rendered HTML is injected via `v-html`.
- Use `navigator.clipboard.writeText()` with a `document.execCommand` fallback.
- Style the wrapper and button in `main.css` or scoped styles.

## Effort Estimate
Small–Medium (Frontend: custom markdown-it fence renderer + CSS)
EOF

create_issue_if_missing \
  "Add copy-to-clipboard button on code blocks in chat messages" \
  /tmp/ux_issue_body.md \
  "enhancement,ux,developer-experience"

# ── Issue 4: System prompt / custom instructions ──────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a power user, I want to define a custom system prompt for a chat so I can configure the AI's persona, output format, and behaviour for my specific use case without repeating instructions in every message.

## Problem
There is no way to attach a system-level instruction to a conversation. Users who want the AI to always respond in a specific format, language, or role must re-state their instructions in every message, wasting context tokens and creating tedious repetition.

## Proposed Solution
Add an optional **system prompt** field to each chat. The system prompt is prepended as a `SYSTEM` role message when constructing the AI request. Users can set, edit, and clear it via a dialog accessible from the chat header.

## Acceptance Criteria
- [ ] Users can open a "System prompt" dialog/panel from the chat header.
- [ ] The system prompt is stored per chat in the database.
- [ ] The system prompt is included as the first message (role: `SYSTEM`) in every AI call for that chat.
- [ ] A visual indicator (e.g., a small icon in the sidebar or chat header) shows when a system prompt is active.
- [ ] Users can edit or clear the system prompt at any time.
- [ ] Existing chats without a system prompt are unaffected.
- [ ] The system prompt is **not** rendered as a chat bubble in the conversation view.

## Technical Notes
- **Database**: New Flyway migration adding a nullable `system_prompt TEXT` column to the `chats` table.
- **Backend**: Update `ChatEntity` and `ChatResponseDTO` to expose `systemPrompt`. Update `ChatService.streamMessage` / `sendMessage` to prepend a SYSTEM role message when `systemPrompt` is set. Extend `PATCH /chats/{chatId}` (or add a dedicated endpoint) to update the field.
- **Frontend**: Add a "System prompt" icon button in the chat header. Store `systemPrompt` in the `Conversation` interface in `useChatStore`. Display the value in a `UModal` with a `<textarea>`.

## Effort Estimate
Medium (DB migration + backend update + frontend modal UI)
EOF

create_issue_if_missing \
  "Allow users to set a system prompt / custom instructions per chat" \
  /tmp/ux_issue_body.md \
  "enhancement,feature,ux"

# ── Issue 5: Edit user messages ───────────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user, I want to edit one of my previous messages and get a fresh AI response from that point, so I can fix a typo or rephrase my question without starting a new chat or sending a follow-up correction.

## Problem
Once a message is sent there is no way to modify it. If a user realises they phrased a question poorly or included an error, their only options are to send a clarifying follow-up (which clutters the conversation) or delete the entire chat and start over.

## Proposed Solution
Show an "Edit" icon on user messages (on hover / focus). Clicking it makes the message text editable inline. On save, the system:
1. Updates the message content in the database.
2. Deletes all subsequent messages (the old AI response and any further turns).
3. Re-sends the updated message to the AI and streams a new response.

## Acceptance Criteria
- [ ] Hovering over a user message reveals an edit (pencil) icon button.
- [ ] Clicking the icon replaces the message bubble with an inline textarea pre-filled with the current content.
- [ ] Save and Cancel buttons appear below the textarea.
- [ ] On save: message is updated, all subsequent messages are removed, and a new AI response is streamed.
- [ ] On cancel: no changes are made and the textarea is dismissed.
- [ ] The UI shows the normal streaming state while the regenerated response is arriving.
- [ ] The edit option is disabled while a stream is in progress.
- [ ] File attachments on the original message are preserved unless the user removes them.

## Technical Notes
- **Backend**: New endpoint `PATCH /chats/{chatId}/messages/{messageId}` that updates the message content and deletes all messages created after it (ordered by `createdAt`).
- **Frontend**: Track an `editingMessageId` ref in the chat store. Render a `<textarea>` when a message's ID matches. On submit, call the backend endpoint and then trigger `streamMessage`.
- **DB**: No schema changes needed — `MessageEntity.message` is already a `TEXT` column.

## Effort Estimate
Medium–Large (Backend endpoint + delete-downstream logic + frontend edit mode)
EOF

create_issue_if_missing \
  "Allow editing a previous user message and regenerating the AI response" \
  /tmp/ux_issue_body.md \
  "enhancement,feature,ux"

# ── Issue 6: Export chat conversation ────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user, I want to download a copy of my chat conversation so I can save it for future reference, share it with colleagues, or use it in other tools — without needing to manually copy-paste every message.

## Problem
There is no export or download feature. Once a conversation is deleted or an account is closed, the content is permanently lost. Power users also want to share interesting AI conversations externally or feed them into other workflows.

## Proposed Solution
Add an **Export** option in the chat's action menu (e.g., a button in the chat header or the sidebar context menu). The export is entirely client-side: format the in-memory messages and trigger a browser file download.

Two supported formats:
- **Markdown** — human-readable; each turn prefixed with `### User` / `### Assistant`; code blocks and reasoning sections preserved.
- **JSON** — full structured export with metadata (model used, timestamps, token counts, reasoning).

## Acceptance Criteria
- [ ] An "Export chat" option appears in the chat header or sidebar context menu.
- [ ] Selecting it opens a small dialog to choose the format (Markdown / JSON).
- [ ] Clicking "Download" triggers a browser file download with a sensible filename (e.g., `my-chat-title-2025-01-15.md`).
- [ ] Markdown export includes: all messages in order, code blocks, and reasoning sections (if present).
- [ ] JSON export includes: chat metadata, all messages with role, content, timestamps, and token counts.
- [ ] The export uses already-loaded in-memory data — no extra API call is required.

## Technical Notes
- **Frontend only** — no backend changes needed.
- Add an `exportChat(format: 'markdown' | 'json')` utility (e.g., a new `useChatExport.ts` composable).
- Use the `Blob` + `URL.createObjectURL` + programmatic `<a download>` click pattern.
- Wire the action to a new menu item in `ChatSidebar.vue` or a button in the chat header inside `chat.vue`.

## Effort Estimate
Small (Frontend only — message formatting + Blob download trigger)
EOF

create_issue_if_missing \
  "Export chat conversation as Markdown or JSON" \
  /tmp/ux_issue_body.md \
  "enhancement,feature,ux"

# ── Issue 7: Drag-and-drop + paste file upload ────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user, I want to drag files onto the chat input area or paste images directly from my clipboard so I can attach files much faster than navigating the file picker dialog.

## Problem
Currently, attaching a file requires clicking the paperclip icon and navigating the native OS file picker. This breaks the typing flow. Most modern chat UIs (Slack, ChatGPT, etc.) support drag-and-drop and Ctrl/Cmd+V paste as the primary interaction patterns.

## Proposed Solution
Add two additional upload entry points alongside the existing file picker:
1. **Drag-and-drop**: Dragging files onto the chat panel shows a drop-zone overlay and accepts files on drop.
2. **Clipboard paste**: Pressing Ctrl/Cmd+V when the chat input is focused and the clipboard contains an image attaches it as a file.

## Acceptance Criteria
- [ ] Dragging a file over the chat panel shows a visible drop-zone overlay (dashed border + "Drop files here" label).
- [ ] Dropping files adds them to the attachment list, identical to using the file picker.
- [ ] Multiple files can be dragged and dropped at once.
- [ ] Pressing Ctrl/Cmd+V when the input is focused and the clipboard contains an image attaches the image.
- [ ] All existing file count (max 5) and size limits (5 MB per file, 20 MB total) still apply; excess files are rejected with a toast warning.
- [ ] Non-image types cannot be pasted from clipboard (drag-and-drop still works for any type).
- [ ] The drop-zone overlay is dismissed if the user drags files away without dropping.

## Technical Notes
- **Frontend only** — handle `dragover`, `dragleave`, and `drop` events on the chat container in `chat.vue`.
- For clipboard paste, listen for the `paste` event on the input, check `event.clipboardData.files`, and append valid image files to the attachment list.
- Reuse the existing `attachments` ref and file-handling logic already present in `chat.vue`.
- The visual overlay can be an absolutely-positioned `<div>` toggled by an `isDraggingOver` reactive ref.

## Effort Estimate
Small–Medium (Frontend: drag/paste event handlers + drop-zone overlay)
EOF

create_issue_if_missing \
  "Support drag-and-drop and clipboard paste for file uploads in the chat input" \
  /tmp/ux_issue_body.md \
  "enhancement,ux"

# ── Issue 8: Persist model selection ─────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a regular user who primarily uses one or two models, I want the app to remember which model I last selected so I do not have to reselect it every time I open the app or start a new chat.

## Problem
The model selector resets to the first available model every time the page is loaded or a new chat is created. Users who have a preferred model (e.g., GPT-4o or Claude 3.5 Sonnet) must manually reselect it on every session — a repetitive and unnecessary friction point.

## Proposed Solution
Persist the last-selected model (provider + modelId) to **localStorage** as part of `useUserSettings`. When creating a new chat, default to the persisted model if it is still available in the loaded model catalog. If it is no longer available, fall back to the first available model.

## Acceptance Criteria
- [ ] After a user selects a model in the chat, the selection is saved to localStorage.
- [ ] On the next session (page reload), new chats default to the last-used model.
- [ ] If the saved model is no longer in the catalog (e.g., deprecated), the app silently falls back to the first available model.
- [ ] The model selection for an existing chat is still stored per-chat (as it is today) and takes precedence over the persisted default.
- [ ] Changing the model in one chat does not retroactively change the model in other existing chats.

## Technical Notes
- **Frontend only** — extend `useUserSettings.ts` to persist a `lastUsedModel: { provider: string; value: string } | null` field alongside `backendBaseUrl`.
- In `useChatStore.ts`, when resolving `defaultModel`, check if the persisted model exists in `models.value` and prefer it.
- Update the model-set handler in `chat.vue` (`setModelForChat`) to also write to user settings.
- Bump the localStorage key version if the settings shape changes (e.g., `chat-sribiddi:user-settings:v3`).

## Effort Estimate
Small (Frontend only — extend useUserSettings + update defaultModel resolution)
EOF

create_issue_if_missing \
  "Persist the last-used model selection across sessions and new chats" \
  /tmp/ux_issue_body.md \
  "enhancement,ux"

# ── Issue 9: Context window usage indicator ───────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user in a long conversation, I want to see how much of the model's context window I have used so I know when I am approaching the limit and should start a new chat before the AI starts losing earlier context.

## Problem
As conversations grow, AI models silently start forgetting earlier messages when the context window is exceeded. Users have no visibility into how "full" the context is. The app already tracks token counts per message (`totalTokens`, `promptTokens`), but this data is only shown per-message and not aggregated in a meaningful way.

## Proposed Solution
Add a small progress indicator near the chat input footer showing the cumulative prompt token count relative to the selected model's `contextLength`. The bar changes colour (green → amber → red) as it fills up, with a tooltip showing exact counts.

## Acceptance Criteria
- [ ] A compact token usage bar or label is visible near the chat input (e.g., in the footer row next to the model selector).
- [ ] It displays something like `12,450 / 128,000 tokens (9%)` or a visual progress bar.
- [ ] Colour transitions: green (0–70 %), amber (70–90 %), red (90–100 %).
- [ ] Hovering shows a tooltip with exact numbers and the model name.
- [ ] The count is based on the most recent message's `promptTokens` value (already returned by the backend).
- [ ] If the model's `contextLength` is unknown, the indicator is hidden or shows only the raw token count.
- [ ] The indicator updates after each AI response completes.

## Technical Notes
- **Frontend only** — no backend changes required. `tokenUsageMap` and `models` are already in `useChatStore`.
- Add a computed `contextUsage` in `chat.vue` (or as a getter in `useChatStore`) that reads `promptTokens` from the last assistant message and divides by the selected model's `contextLength`.
- Use a `UProgress` component or a custom styled `<div>` in the chat input footer.
- `ModelOption.contextLength` already contains the context window size from the model catalog.

## Effort Estimate
Small (Frontend only — computed value + progress bar UI in the chat footer)
EOF

create_issue_if_missing \
  "Show a context window usage indicator near the chat input" \
  /tmp/ux_issue_body.md \
  "enhancement,feature,ux"

# ── Issue 10: Message timestamps ─────────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a user, I want to see when each message was sent so I can track the timeline of my conversation and know how long AI responses took to generate.

## Problem
Chat messages display no timestamps. Users cannot tell when a conversation happened, how recent it is, or how long individual AI responses took to generate. The `createdAt` field already exists on `MessageEntity` and is included in `MessageResponseDTO` — it is simply not surfaced in the UI.

## Proposed Solution
Show a relative timestamp (e.g., "2 minutes ago", "Yesterday at 14:32") on each message. The exact ISO timestamp is shown in a tooltip on hover. Timestamps refresh periodically (every minute) while the page is open.

## Acceptance Criteria
- [ ] Each message shows a relative timestamp (e.g., "just now", "5 min ago", "Jan 15").
- [ ] Hovering over the timestamp shows the full localized date/time (e.g., "January 15, 2025 at 14:32").
- [ ] Timestamps are based on the `createdAt` field already present in `MessageResponseDTO`.
- [ ] Streamed messages show a timestamp once the stream completes (or use the optimistic send time).
- [ ] Timestamps use the user's local timezone.
- [ ] The relative label updates while the page is open (re-renders every 60 seconds).

## Technical Notes
- **Frontend only** — `MessageResponseDTO.createdAt` (Instant → ISO string) is already returned by the backend. Ensure it is mapped in the frontend `Message` interface in `useChatStore.ts`.
- Use the `Intl.RelativeTimeFormat` API (no new dependency needed) for relative formatting.
- Add a small timestamp element below or beside each message bubble in `chat.vue`, styled subtly (muted text, small font-size).
- Use a `setInterval` (or a `useIntervalFn` composable) to re-render timestamps every 60 seconds.

## Effort Estimate
Small (Frontend only — surface the existing `createdAt` field + relative formatting utility)
EOF

create_issue_if_missing \
  "Display send timestamps on chat messages" \
  /tmp/ux_issue_body.md \
  "enhancement,ux"

# ── Issue 11: Keyboard shortcuts ─────────────────────────────────────────────

cat > /tmp/ux_issue_body.md << 'EOF'
## User Story
As a power user, I want keyboard shortcuts for frequent actions like creating a new chat, navigating between conversations, and stopping a stream so I can use the app efficiently without reaching for the mouse.

## Problem
All interactions in the app currently require mouse or trackpad input. There are no keyboard shortcuts — not even for creating a new chat or switching between conversations. This makes the app slow and frustrating for keyboard-oriented users.

## Proposed Solution
Implement a set of well-chosen keyboard shortcuts and a discoverable help overlay.

| Shortcut | Action |
|---|---|
| Ctrl/Cmd + K | Open chat search / command palette |
| Ctrl/Cmd + N | Create a new chat |
| Ctrl/Cmd + Shift + Up/Down | Navigate to previous / next chat |
| Escape | Stop the current streaming response |
| Shift + Enter | Insert a newline in the message input |
| ? (input not focused) | Open keyboard shortcut help overlay |

## Acceptance Criteria
- [ ] All shortcuts in the table above are implemented and functional.
- [ ] Pressing `?` when the message input is not focused displays a modal listing all shortcuts.
- [ ] Shortcuts do not fire when the user is typing in an input or textarea.
- [ ] `Escape` stops the active SSE stream (equivalent to clicking the Stop button).
- [ ] `Ctrl/Cmd + N` creates a new chat and focuses the message input.
- [ ] Shortcuts work on both macOS (Cmd) and Windows/Linux (Ctrl).
- [ ] The help overlay uses platform-appropriate key labels (⌘ vs Ctrl).

## Technical Notes
- **Frontend only** — register a global `keydown` listener in `app.vue` or a dedicated `useKeyboardShortcuts` composable.
- The composable should accept a map of `{ shortcut: string, handler: () => void }`, guard against input-focused state, and clean up on `onUnmounted`.
- Reuse existing store actions: `createChat()`, `setActiveChat()`, and the existing stop-stream mechanism.
- The help modal can be a simple `UModal` containing a shortcut reference table.

## Effort Estimate
Medium (Frontend: global keydown composable + help modal + wiring existing store actions)
EOF

create_issue_if_missing \
  "Add keyboard shortcuts for common chat actions" \
  /tmp/ux_issue_body.md \
  "enhancement,ux,developer-experience"

echo "All issues processed successfully."
