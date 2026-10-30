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

function amountInput(value, label, placeholder) {
  const input = document.createElement("input");
  input.className = "input";
  input.type = "number";
  input.min = "0";
  input.max = "10000";
  input.step = "0.01";
  input.inputMode = "decimal";
  input.placeholder = placeholder;
  input.value = value === null ? "" : Number(value).toFixed(2);
  input.setAttribute("aria-label", label);
  return input;
}

function marginText(price, cost) {
  if (price === "" || cost === "") return price === "" ? "Price not set" : "Add the cost to see the margin";
  const margin = Number(price) - Number(cost);
  const percent = Number(price) > 0 ? ` (${Math.round(margin / Number(price) * 100)}%)` : "";
  return `${money.format(margin)} a plate${percent}`;
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
  const save = document.createElement("button");
  save.type = "button";
  save.className = "secondary-button";
  save.textContent = "Save";
  save.setAttribute("aria-label", `Save the price of ${dish.name}`);

  let saved = [price.value, cost.value];
  const update = () => {
    margin.textContent = marginText(price.value, cost.value);
    save.disabled = price.value === "" || (price.value === saved[0] && cost.value === saved[1]);
  };
  price.addEventListener("input", update);
  cost.addEventListener("input", update);
  update();

  save.addEventListener("click", async () => {
    save.disabled = true;
    try {
      const result = await apiRequest(`/api/dishes/${dish.id}/price`, {
        method: "PUT",
        body: JSON.stringify({ price: Number(price.value), unitCost: cost.value === "" ? null : Number(cost.value) })
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
