export function toLocalInputValue(str) {
  if (!str) return '';
  let cleaned = str.trim().replace('T', ' ');
  const match = cleaned.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2}):(\d{2})/);
  if (match) {
    const [, date, hour, min, sec] = match;
    return `${date}T${hour}:${min}:${sec}`;
  }
  return '';
}

// Convert "YYYY-MM-DDTHH:mm" (chars of a second if missing) into the backend's
// expected "YYYY-MM-DD HH:mm:ss" format.
export function fromLocalInputValue(val) {
  if (!val) return '';
  let formatted = val.replace('T', ' '); // Use a space instead of 'T'

  if (formatted.length === 16) {
    formatted += ':00'; // Appends seconds if missing -> "YYYY-MM-DD HH:mm:ss"
  } else if (formatted.length > 19) {
    formatted = formatted.substring(0, 19);
  }

  return formatted; // Returns e.g. "2026-07-28 18:33:27"
}

// Robust parsing to numeric epoch milliseconds for reliable comparison
export function parseToComparable(str) {
  if (!str) return 0;
  const formatted = str.includes('T') ? str : str.replace(' ', 'T');
  const timeValue = new Date(formatted).getTime();
  return isNaN(timeValue) ? 0 : timeValue;
}