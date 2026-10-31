// Dishes page: each dish's price and unit cost (issue #43). Needs js/api.js.

const priceList = document.getElementById("price-list");
const priceStatus = document.getElementById("price-status");
const money = new Intl.NumberFormat("en-SG", { style: "currency", currency: "SGD", currencyDisplay: "narrowSymbol" });

function showStatus(message, type, element) {
  element.textContent = message;
  element.className = `status-text${type ? ` is-${type}` : ""}`;
}

// A box for an amount in S$, captioned on phones, where the column headers are hidden.
function amountField(caption, value, label) {
  const field = document.createElement("label");
  field.className = "price-field";
  const text = document.createElement("span");
  text.className = "price-caption";
  text.textContent = caption;
  const input = amountInput(value, label, caption);
  field.append(text, input);
  return { field, input };
}

// A text box rather than type="number": a number box silently drops what it can't read ("4,50"
// became 450) and reports "" for it, which left Save greyed out with no reason given.
function amountInput(value, label, placeholder) {
  const input = document.createElement("input");
  input.className = "input";
  input.type = "text";
  input.inputMode = "decimal";
  input.autocomplete = "off";
  input.placeholder = placeholder;
  input.value = value === null ? "" : Number(value).toFixed(2);
  input.setAttribute("aria-label", label);
  return input;
}

// An amount in S$ as typed: "4.50", "4.5", "$4.50" or "4,50". null when the box is empty, NaN
// when it isn't an amount from 0 to 10,000 with at most 2 decimals.
function parseAmount(text) {
  const cleaned = text.trim().replace(/^S?\$\s*/i, "").replace(",", ".");
  if (cleaned === "") return null;
  return /^\d{1,5}(\.\d{1,2})?$/.test(cleaned) && Number(cleaned) <= 10000 ? Number(cleaned) : NaN;
}

function marginText(price, cost) {
  if (price === null) return cost === null ? "Price not set" : "Add the price too, then save";
  if (Number.isNaN(price) || Number.isNaN(cost)) return "Use an amount like 4.50";
  if (cost === null) return "Add the cost to see the margin";
  const margin = price - cost;
  const percent = price > 0 ? ` (${Math.round(margin / price * 100)}%)` : "";
  return `${money.format(margin)} a plate${percent}`;
}

// Why the row can't be saved yet, or null when it can.
function problem(dishName, price, cost) {
  if (price === null) return `Add a price for ${dishName} as well. The cost is saved together with the price.`;
  if (Number.isNaN(price)) return `Type ${dishName}’s price as an amount like 4.50.`;
  if (Number.isNaN(cost)) return `Type ${dishName}’s cost as an amount like 1.70, or leave it empty.`;
  return null;
}

function priceRow(dish) {
  const row = document.createElement("div");
  row.className = "price-row";
  row.setAttribute("role", "row");

  const name = document.createElement("span");
  name.textContent = dish.name;
  const { field: priceField, input: price } = amountField("Price", dish.price, `Price of ${dish.name} in S$`);
  const { field: costField, input: cost } = amountField("Cost", dish.unitCost, `Cost of one ${dish.name} in S$`);
  const margin = document.createElement("span");
  margin.className = "price-margin";
  // Also where the row says what's missing when Save is pressed, so screen readers announce it.
  margin.setAttribute("aria-live", "polite");
  const save = document.createElement("button");
  save.type = "button";
  save.className = "secondary-button";
  save.textContent = "Save";
  save.setAttribute("aria-label", `Save the price of ${dish.name}`);

  let saved = [price.value, cost.value];
  // Save stays pressable whenever something changed; pressing it explains anything missing.
  const update = () => {
    margin.textContent = marginText(parseAmount(price.value), parseAmount(cost.value));
    margin.classList.remove("is-error");
    price.removeAttribute("aria-invalid");
    cost.removeAttribute("aria-invalid");
    save.disabled = price.value === saved[0] && cost.value === saved[1];
  };
  price.addEventListener("input", update);
  cost.addEventListener("input", update);
  update();

  save.addEventListener("click", async () => {
    const [priceAmount, costAmount] = [parseAmount(price.value), parseAmount(cost.value)];
    const missing = problem(dish.name, priceAmount, costAmount);
    if (missing) {
      const field = priceAmount === null || Number.isNaN(priceAmount) ? price : cost;
      field.setAttribute("aria-invalid", "true");
      field.focus();
      margin.textContent = missing;
      margin.classList.add("is-error");
      return;
    }

    save.disabled = true;
    try {
      const result = await apiRequest(`/api/dishes/${dish.id}/price`, {
        method: "PUT",
        body: JSON.stringify({ price: priceAmount, unitCost: costAmount })
      });
      price.value = Number(result.price).toFixed(2);
      cost.value = result.unitCost === null ? "" : Number(result.unitCost).toFixed(2);
      saved = [price.value, cost.value];
      update();
      showStatus(`Saved ${dish.name}: ${money.format(Number(result.price))} from today.`, "success", priceStatus);
    } catch (error) {
      showStatus(error.message || "The price could not be saved.", "error", priceStatus);
      update();
    }
  });

  row.append(name, priceField, costField, margin, save);
  return row;
}

async function loadPrices() {
  try {
    await apiRequest("/api/profile");
    const dishes = await apiRequest("/api/dishes");
    if (!dishes.length) {
      const empty = document.createElement("p");
      empty.className = "empty-state";
      empty.textContent = "Your dishes appear here once you import sales or record them with the assistant.";
      priceList.replaceChildren(empty);
      return;
    }
    priceList.replaceChildren(...dishes.map(priceRow));
  } catch (error) {
    if (error.status === 401) window.location.href = "index.html";
    else showStatus("Dishes could not be loaded.", "error", priceStatus);
  }
}

loadPrices();

// Sent by js/assistant.js when the user confirms a price change.
window.addEventListener("chicky:prices-saved", loadPrices);
