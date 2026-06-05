package com.rft.chat_sribiddi.providers;

import com.rft.chat_sribiddi.chat.dto.RequestDTO.Provider;
import com.rft.chat_sribiddi.providerconnection.ProviderAuthMethod;
import com.rft.chat_sribiddi.providerconnection.ProviderConnectionStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ProviderCatalogService {

  private final Map<String, ProviderDescriptor> providers;
  private final Map<String, String> aliases;

  public ProviderCatalogService() {
    List<ProviderDescriptor> entries = buildCatalog();
    Map<String, ProviderDescriptor> byId = new LinkedHashMap<>();
    for (ProviderDescriptor entry : entries) {
      byId.put(entry.id(), entry);
    }
    providers = Map.copyOf(byId);
    aliases = buildAliases();
  }

  public List<ProviderDescriptor> listProviders() {
    return providers.values().stream()
        .sorted(Comparator.comparing(ProviderDescriptor::label, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  public Optional<ProviderDescriptor> find(String providerId) {
    return Optional.ofNullable(providers.get(canonicalProviderId(providerId)));
  }

  public String canonicalProviderId(String providerId) {
    if (providerId == null) {
      return "";
    }
    String normalized =
        providerId.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    return aliases.getOrDefault(normalized, normalized);
  }

  public String providerId(Provider provider) {
    return switch (provider) {
      case OPENAI -> "openai";
      case GOOGLE -> "google";
      case CLAUDE -> "anthropic";
      case OPENROUTER -> "openrouter";
      case BRAVE_SEARCH -> "brave-search";
    };
  }

  public Optional<Provider> legacyProvider(String providerId) {
    return switch (canonicalProviderId(providerId)) {
      case "openai" -> Optional.of(Provider.OPENAI);
      case "google" -> Optional.of(Provider.GOOGLE);
      case "anthropic" -> Optional.of(Provider.CLAUDE);
      case "openrouter" -> Optional.of(Provider.OPENROUTER);
      case "brave-search" -> Optional.of(Provider.BRAVE_SEARCH);
      default -> Optional.empty();
    };
  }

  private Map<String, String> buildAliases() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("openai", "openai");
    map.put("google", "google");
    map.put("gemini", "google");
    map.put("claude", "anthropic");
    map.put("anthropic", "anthropic");
    map.put("openrouter", "openrouter");
    map.put("brave-search", "brave-search");
    map.put("brave", "brave-search");
    map.put("brave_search", "brave-search");
    map.put("qwen-oauth-portal", "qwen-oauth");
    map.put("z-ai", "zai");
    map.put("z.ai", "zai");
    map.put("github-copilot", "github-copilot");
    map.put("copilot", "github-copilot");
    return map;
  }

  private List<ProviderDescriptor> buildCatalog() {
    List<ProviderDescriptor> list = new ArrayList<>();

    add(list, "openai", "OpenAI", "llm", true, false, false, "native",
        ProviderConnectionStatus.NEEDS_CONNECTION,
        ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_AUTH_CODE);
    add(list, "anthropic", "Anthropic Claude", "llm", true, false, false, "native",
        ProviderConnectionStatus.NEEDS_CONNECTION,
        ProviderAuthMethod.API_KEY, ProviderAuthMethod.CLI_SESSION);
    add(list, "google", "Google Gemini", "llm", true, false, false, "native",
        ProviderConnectionStatus.NEEDS_CONNECTION,
        ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_AUTH_CODE,
        ProviderAuthMethod.SERVICE_ACCOUNT);
    add(list, "openrouter", "OpenRouter", "llm", true, false, false, "native",
        ProviderConnectionStatus.NEEDS_CONNECTION,
        ProviderAuthMethod.API_KEY, ProviderAuthMethod.OAUTH_PKCE);
    add(list, "brave-search", "Brave Search", "tool", false, false, false, "tool",
        ProviderConnectionStatus.NEEDS_CONNECTION, ProviderAuthMethod.API_KEY);

    add(list, "github-copilot", "GitHub Copilot", "llm", true, false, true, "gateway",
        ProviderConnectionStatus.EXPERIMENTAL, ProviderAuthMethod.DEVICE_CODE,
        ProviderAuthMethod.SUBSCRIPTION_CLI);
    add(list, "qwen", "Qwen Cloud", "llm", true, false, false, "gateway",
        ProviderConnectionStatus.NEEDS_CONNECTION, ProviderAuthMethod.API_KEY,
        ProviderAuthMethod.OPENAI_COMPATIBLE);
    add(list, "qwen-oauth", "Qwen OAuth / Portal", "llm", true, false, true, "gateway",
        ProviderConnectionStatus.EXPERIMENTAL, ProviderAuthMethod.OAUTH_AUTH_CODE);
    add(list, "minimax", "MiniMax", "llm", true, false, false, "gateway",
        ProviderConnectionStatus.NEEDS_CONNECTION, ProviderAuthMethod.API_KEY);
    add(list, "minimax-portal", "MiniMax Portal", "llm", true, false, true, "gateway",
        ProviderConnectionStatus.EXPERIMENTAL, ProviderAuthMethod.OAUTH_AUTH_CODE);
    add(list, "zai", "Z.AI", "llm", true, false, false, "gateway",
        ProviderConnectionStatus.NEEDS_CONNECTION, ProviderAuthMethod.API_KEY,
        ProviderAuthMethod.OPENAI_COMPATIBLE);

    for (String[] row :
        List.of(
            row("alibaba-model-studio", "Alibaba Model Studio", "llm"),
            row("amazon-bedrock", "Amazon Bedrock", "llm"),
            row("amazon-bedrock-mantle", "Amazon Bedrock Mantle", "llm"),
            row("arcee-ai", "Arcee AI", "llm"),
            row("azure-openai", "Azure OpenAI", "llm"),
            row("azure-speech", "Azure Speech", "audio"),
            row("baseten", "Baseten", "llm"),
            row("cerebras", "Cerebras", "llm"),
            row("chutes", "Chutes", "llm"),
            row("claude-max-api-proxy", "Claude Max API Proxy", "llm"),
            row("cloudflare-ai-gateway", "Cloudflare AI Gateway", "gateway"),
            row("cohere", "Cohere", "llm"),
            row("comfyui", "ComfyUI", "image"),
            row("custom-http", "Custom HTTP", "llm"),
            row("dashscope", "DashScope", "llm"),
            row("deepgram", "Deepgram", "audio"),
            row("deepinfra", "DeepInfra", "llm"),
            row("deepseek", "DeepSeek", "llm"),
            row("ds4", "ds4", "local"),
            row("eden-ai", "Eden AI", "llm"),
            row("elevenlabs", "ElevenLabs", "audio"),
            row("fal", "fal", "image"),
            row("fireworks", "Fireworks AI", "llm"),
            row("github-models", "GitHub Models", "llm"),
            row("gmi-cloud", "GMI Cloud", "llm"),
            row("glm", "GLM", "llm"),
            row("gradium", "Gradium", "audio"),
            row("grok", "Grok", "llm"),
            row("groq", "Groq", "llm"),
            row("huggingface", "Hugging Face Inference", "llm"),
            row("inferrs", "Inferrs", "local"),
            row("inworld", "Inworld", "llm"),
            row("kilo-gateway", "Kilo Gateway", "gateway"),
            row("lambda-labs", "Lambda Labs", "llm"),
            row("litellm", "LiteLLM", "gateway"),
            row("llama-api", "Llama API", "llm"),
            row("llama-cpp", "llama.cpp", "local"),
            row("lm-studio", "LM Studio", "local"),
            row("mistral", "Mistral AI", "llm"),
            row("modal", "Modal", "llm"),
            row("modelscope", "ModelScope", "llm"),
            row("moonshot", "Moonshot AI", "llm"),
            row("nebius-ai", "Nebius AI", "llm"),
            row("novita", "NovitaAI", "llm"),
            row("nvidia", "NVIDIA", "llm"),
            row("ollama", "Ollama", "local"),
            row("ollama-cloud", "Ollama Cloud", "llm"),
            row("opencode", "OpenCode", "llm"),
            row("opencode-go", "OpenCode Go", "llm"),
            row("openai-chat-legacy", "OpenAI Chat Completion Legacy", "llm"),
            row("openai-compatible", "OpenAI Compatible", "llm"),
            row("openai-responses", "OpenAI Responses API", "llm"),
            row("ovhcloud", "OVHcloud", "llm"),
            row("paperspace", "Paperspace", "llm"),
            row("perplexity", "Perplexity AI", "llm"),
            row("pixverse", "PixVerse", "video"),
            row("prediction-guard", "Prediction Guard", "llm"),
            row("qianfan", "Qianfan", "llm"),
            row("replicate", "Replicate", "llm"),
            row("runpod", "RunPod", "llm"),
            row("runway", "Runway", "video"),
            row("sambanova", "SambaNova", "llm"),
            row("scaleway", "Scaleway", "llm"),
            row("senseaudio", "SenseAudio", "audio"),
            row("sglang", "SGLang", "local"),
            row("siliconflow", "SiliconFlow", "llm"),
            row("stepfun", "StepFun", "llm"),
            row("synthetic", "Synthetic", "llm"),
            row("tencent-tokenhub", "Tencent Cloud TokenHub", "llm"),
            row("together-ai", "Together AI", "llm"),
            row("venice", "Venice AI", "llm"),
            row("vercel-ai-gateway", "Vercel AI Gateway", "gateway"),
            row("vertex-ai", "Vertex AI", "llm"),
            row("vllm", "vLLM", "local"),
            row("volcengine", "Volcengine Doubao", "llm"),
            row("voyage-ai", "Voyage AI", "embedding"),
            row("vydra", "Vydra", "llm"),
            row("xai", "xAI", "llm"),
            row("xiaomi-mimo", "Xiaomi MiMo", "llm"),
            row("ai21", "AI21", "llm"),
            row("aleph-alpha", "Aleph Alpha", "llm"),
            row("akash-network", "Akash Network", "llm"),
            row("anyscale", "Anyscale", "llm"),
            row("bentoml", "BentoML", "llm"),
            row("clarifai", "Clarifai", "llm"),
            row("mock", "Mock / Testing", "testing"))) {
      boolean local = "local".equals(row[2]);
      ProviderConnectionStatus status =
          local ? ProviderConnectionStatus.NEEDS_LOCAL_ENDPOINT : ProviderConnectionStatus.NEEDS_CONNECTION;
      add(list, row[0], row[1], row[2], !"audio".equals(row[2]), local, false, "gateway", status,
          local ? new ProviderAuthMethod[] {ProviderAuthMethod.LOCAL_ENDPOINT}
              : new ProviderAuthMethod[] {ProviderAuthMethod.API_KEY, ProviderAuthMethod.OPENAI_COMPATIBLE});
    }

    return List.copyOf(list);
  }

  private static String[] row(String id, String label, String category) {
    return new String[] {id, label, category};
  }

  private static void add(
      List<ProviderDescriptor> list,
      String id,
      String label,
      String category,
      boolean chatProvider,
      boolean localOnly,
      boolean experimental,
      String execution,
      ProviderConnectionStatus defaultStatus,
      ProviderAuthMethod... authMethods) {
    list.add(
        new ProviderDescriptor(
            id,
            label,
            category,
            chatProvider,
            localOnly,
            experimental,
            execution,
            defaultStatus,
            List.copyOf(Arrays.asList(authMethods))));
  }

  public record ProviderDescriptor(
      String id,
      String label,
      String category,
      boolean chatProvider,
      boolean localOnly,
      boolean experimental,
      String execution,
      ProviderConnectionStatus defaultStatus,
      List<ProviderAuthMethod> authMethods) {}
}
