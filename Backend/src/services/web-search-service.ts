import { env } from "../config/env.js";

export type WebSearchResult = {
  title: string;
  url: string;
  description: string;
};

export class WebSearchService {
  async search(query: string, apiKey: string | null): Promise<WebSearchResult[]> {
    if (!apiKey?.trim() || !query.trim()) return [];
    const url = new URL("https://api.search.brave.com/res/v1/web/search");
    url.searchParams.set("q", query.trim());
    url.searchParams.set("count", String(env.WEB_SEARCH_MAX_RESULTS));
    const response = await fetch(url, {
      headers: {
        Accept: "application/json",
        "Accept-Encoding": "gzip",
        "X-Subscription-Token": apiKey.trim(),
        "User-Agent": env.WEB_SEARCH_USER_AGENT,
      },
      signal: AbortSignal.timeout(env.webSearchTimeoutMs),
    });
    if (!response.ok) return [];
    const json = (await response.json()) as {
      web?: { results?: Array<{ title?: string; url?: string; description?: string }> };
    };
    return (json.web?.results ?? []).flatMap((item) => {
      if (!item.title || !item.url) return [];
      return [{ title: item.title, url: item.url, description: item.description ?? "" }];
    });
  }

  format(results: WebSearchResult[]) {
    if (results.length === 0) return "";
    return [
      "Relevant web search results:",
      ...results.map(
        (item, index) => `${index + 1}. ${item.title}\n${item.url}\n${item.description}`,
      ),
    ].join("\n\n");
  }
}
