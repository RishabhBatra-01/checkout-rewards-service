"use client";

export function QuantityStepper({
  quantity,
  disabled,
  onChange,
}: {
  quantity: number;
  disabled: boolean;
  onChange: (quantity: number) => void;
}) {
  return (
    <div className="stepper">
      <button
        type="button"
        aria-label="Decrease quantity"
        // The backend rejects a quantity below 1; removing is a separate action.
        disabled={disabled || quantity <= 1}
        onClick={() => onChange(quantity - 1)}
      >
        −
      </button>
      <span className="qty">{quantity}</span>
      <button
        type="button"
        aria-label="Increase quantity"
        disabled={disabled}
        onClick={() => onChange(quantity + 1)}
      >
        +
      </button>
    </div>
  );
}
