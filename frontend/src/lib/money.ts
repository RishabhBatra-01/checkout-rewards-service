/**
 * Money crosses the wire as integer cents and stays an integer until the moment it
 * is rendered. Formatting is done with integer division rather than `cents / 100`,
 * so no floating point value is ever created.
 */
export function formatCents(cents: number): string {
  const negative = cents < 0;
  const absolute = Math.abs(cents);
  const whole = Math.trunc(absolute / 100);
  const fraction = absolute % 100;
  return `${negative ? "-" : ""}$${whole.toLocaleString("en-US")}.${String(fraction).padStart(2, "0")}`;
}
