export const toIso = (value: Date | string | null | undefined) =>
  value == null
    ? null
    : value instanceof Date
      ? value.toISOString()
      : new Date(value).toISOString();

export const now = () => new Date();

export const parseInstantOrDate = (
  value: string | undefined,
  fallback: Date,
  endOfDate: boolean,
) => {
  if (!value?.trim()) return fallback;
  const trimmed = value.trim();
  const asInstant = Date.parse(trimmed);
  if (!Number.isNaN(asInstant)) {
    return new Date(asInstant);
  }
  const match = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) {
    throw new Error(`Invalid date parameter: ${value}`);
  }
  const date = new Date(`${trimmed}T00:00:00.000Z`);
  if (endOfDate) {
    date.setUTCDate(date.getUTCDate() + 1);
  }
  return date;
};
