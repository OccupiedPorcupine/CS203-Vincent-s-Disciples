// Prep list page: portions and ingredients to prepare for a day, each dish's recipe (issue #45),
// and each ingredient's yield and whether it's bought daily (issue #89). Needs js/api.js.

const prepDate = document.getElementById("prep-date");
const prepBuffer = document.getElementById("prep-buffer");
const shareButton = document.getElementById("share-button");
const prepStatus = document.getElementById("prep-status");
const portionList = document.getElementById("portion-list");
const ingredientList = document.getElementById("ingredient-list");
const missingRecipes = document.getElementById("missing-recipes");
const recipeList = document.getElementById("recipe-list");
const recipeStatus = document.getElementById("recipe-status");
const ingredientNames = document.getElementById("ingredient-names");
const buyingList = document.getElementById("buying-list");
const buyingStatus = document.getElementById("buying-status");

// The backend works out prep lists from today up to this many days ahead (Singapore time).
const MAX_DAYS_AHEAD = 7;
const BUFFER_KEY = "chicky-prep-buffer";

let shownList = null;
let stockIngredients = null;
let prepRequest = 0;
let ingredientsRequest = 0;
const inventoryList = document.getElementById("inventory-list");
const inventoryStatus = document.getElementById("inventory-status");
const purchaseOverrides = new Map();

function showStatus(message, type, element) {
  element.textContent = message;
  element.className = `status-text${type ? ` is-${type}` : ""}`;
}

function addDays(isoDate, days) {
  const date = new Date(`${isoDate}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + days);
  return date.toISOString().slice(0, 10);
}

function formatDay(isoDate) {
  return new Date(`${isoDate}T00:00:00Z`).toLocaleDateString("en-SG", {
    weekday: "long", day: "numeric", month: "short", timeZone: "UTC"
  });
}

// 6300 g reads better as 6.3 kg; other units are shown as they are. Litres are "L" so 7 L
// isn't read as 71.
function formatAmount(quantity, unit) {
  const amount = Number(quantity);
  const larger = { g: "kg", ml: "L" }[unit.toLowerCase()];
  if (larger && amount >= 1000) return `${round(amount / 1000)} ${larger}`;
  return `${round(amount)} ${unit}`;
}

function round(value) {
  return Number(value.toFixed(value >= 100 ? 0 : 2)).toLocaleString("en-SG");
}

function emptyState(text) {
  const empty = document.createElement("p");
  empty.className = "empty-state";
  empty.textContent = text;
  return empty;
}

function listRow(main, amount, note) {
  const row = document.createElement("div");
  row.className = "prep-row";
  const name = document.createElement("span");
  name.className = "prep-name";
  name.textContent = main;
  const value = document.createElement("span");
  value.className = "prep-amount";
  value.textContent = amount;
  row.append(name, value);
  if (note) {
    const caption = document.createElement("span");
    caption.className = "prep-caption";
    caption.textContent = note;
    row.append(caption);
  }
  return row;
}

function renderPrepList(list) {
  shownList = list;
  renderInventory();
  shareButton.disabled = Boolean(list.closed) || !list.dishes.length;
  missingRecipes.textContent = "";

  if (list.closed) {
    portionList.replaceChildren(emptyState(list.closed));
    ingredientList.replaceChildren(emptyState("Nothing to prepare."));
    return;
  }

  const skipped = list.skipped.map(skip => listRow(skip.dish, "–", skip.reason));
  if (!list.dishes.length && !skipped.length) {
    portionList.replaceChildren(emptyState("Dishes you’ve sold in the last 4 weeks appear here. Record sales with the assistant or import them on the Data ingestion page."));
  } else {
    portionList.replaceChildren(...list.dishes.map(dish => listRow(
      dish.dish,
      `${dish.portions} portions`,
      `${dish.why} Forecast ${dish.expected} (likely ${dish.low}–${dish.high}) · ${dish.basis}`
    )), ...skipped);
  }

  if (!list.ingredients.length) {
    ingredientList.replaceChildren(emptyState("Add recipes below to see how much of each ingredient you need."));
  } else {
    // What to buy for the day first, then what comes out of stock.
    const groups = [
      ["Usually bought daily", list.ingredients.filter(item => item.perishable)],
      ["Usually kept in stock", list.ingredients.filter(item => !item.perishable)]
    ].filter(([, items]) => items.length);

    ingredientList.replaceChildren(...groups.flatMap(([title, items]) => [
      groupHeading(title),
      ...items.map(item => {
        const stock = stockIngredients?.find(entry =>
          entry.name === item.ingredient && entry.unit === item.unit
        );
        const plan = inventoryPlan(list, item, stock);
        const needed = formatAmount(item.toBuy, item.unit);

        if (plan) {
          const usable = formatAmount(plan.usable, item.unit);
          return listRow(
            item.ingredient,
            `Buy ${formatAmount(plan.purchase, item.unit)}`,
            `${needed} needed for prep − ${usable} usable stock · ${ingredientNote(item)}`
          );
        }

        const stockNote = list.date === addDays(today, 1) ? "Stock unknown · " : "";
        return listRow(item.ingredient, `${needed} needed`, stockNote + ingredientNote(item));
      })
    ]));
  }

  const withoutRecipe = list.dishes.filter(dish => !dish.hasRecipe).map(dish => dish.dish);
  if (list.ingredients.length && withoutRecipe.length) {
    missingRecipes.textContent = `Not counted, no recipe yet: ${withoutRecipe.join(", ")}.`;
  }
}

function groupHeading(text) {
  const heading = document.createElement("h3");
  heading.className = "prep-group";
  heading.textContent = text;
  return heading;
}

// "7.2 kg cooked at 70% yield · For Chicken rice", or just the dishes when cooking doesn't change it.
function ingredientNote(item) {
  const dishes = `For ${item.dishes.join(", ")}`;
  if (Number(item.yieldPercent) === 100) return dishes;
  return `${formatAmount(item.quantity, item.unit)} cooked at ${round(Number(item.yieldPercent))}% yield · ${dishes}`;
}

// Plain text that reads well in WhatsApp.
function prepListText(list) {
  const lines = [`Prep for ${formatDay(list.date)}`, ""];
  list.dishes.forEach(dish => lines.push(`${dish.dish}: ${dish.portions}`));
  [["Daily purchases before stock", list.ingredients.filter(item => item.perishable)],
    ["Ingredients normally kept in stock", list.ingredients.filter(item => !item.perishable)]].forEach(([title, items]) => {
    if (!items.length) return;
    lines.push("", title);
    items.forEach(item => {
      const stock = stockIngredients?.find(entry => entry.name === item.ingredient && entry.unit === item.unit);
      const plan = inventoryPlan(list, item, stock);
      const reviewed = stock ? purchaseOverrides.get(stock.id) : undefined;
      const purchase = reviewed ?? plan?.purchase;
      const note = purchase == null ? " · stock unverified" : ` · buy ${formatAmount(purchase, item.unit)}`;
      lines.push(`${item.ingredient}: ${formatAmount(item.toBuy, item.unit)} for prep${note}`);
    });
  });
  return lines.join("\n");
}

async function loadPrepList() {
  const request = ++prepRequest;
  purchaseOverrides.clear();
  shownList = null;
  renderInventory();
  shareButton.disabled = true;
  showStatus("", null, prepStatus);
  portionList.replaceChildren(emptyState("Working out the forecasts…"));
  ingredientList.replaceChildren(emptyState("Loading…"));
  const buffer = prepBuffer.value === "" ? "" : prepBuffer.value === "top" ? "&rule=top" : `&bufferPercent=${prepBuffer.value}`;
  try {
    const list = await apiRequest(`/api/prep-list?date=${prepDate.value}${buffer}`);
    if (request === prepRequest) renderPrepList(list);
  } catch (error) {
    if (request !== prepRequest) return;
    if (error.status === 401) {
      window.location.href = "index.html";
      return;
    }
    portionList.replaceChildren(emptyState("–"));
    ingredientList.replaceChildren(emptyState("–"));
    showStatus(error.message || "Couldn’t work out the prep list. Try again in a moment.", "error", prepStatus);
  }
}

async function shareList() {
  if (!shownList) return;
  if ([...inventoryList.querySelectorAll(".review-purchase")].some(input => !input.reportValidity())) return;
  const text = prepListText(shownList);
  try {
    if (navigator.share) {
      await navigator.share({ text });
      return;
    }
    await navigator.clipboard.writeText(text);
    showStatus("Copied. Paste it into WhatsApp or a note.", "success", prepStatus);
  } catch (error) {
    if (error.name !== "AbortError") showStatus("Couldn’t share the list. Try again.", "error", prepStatus);
  }
}

// One ingredient line in the recipe editor.
function ingredientLine(dishName, item = { ingredient: "", quantity: "", unit: "" }) {
  const line = document.createElement("div");
  line.className = "recipe-line";

  const name = document.createElement("input");
  name.className = "input";
  name.maxLength = 60;
  name.placeholder = "Ingredient";
  name.value = item.ingredient;
  name.setAttribute("list", "ingredient-names");
  name.setAttribute("aria-label", `Ingredient in ${dishName}`);

  const quantity = document.createElement("input");
  quantity.className = "input";
  quantity.type = "number";
  quantity.min = "0";
  quantity.step = "any";
  quantity.inputMode = "decimal";
  quantity.placeholder = "Cooked amount per portion";
  quantity.value = item.quantity;
  quantity.setAttribute("aria-label", `Cooked amount per portion of ${dishName}`);

  const unit = document.createElement("input");
  unit.className = "input";
  unit.maxLength = 20;
  unit.placeholder = "Unit";
  unit.value = item.unit;
  unit.setAttribute("list", "unit-names");
  unit.setAttribute("aria-label", "Unit");
  // An ingredient used before keeps its unit, so fill it in.
  name.addEventListener("change", () => {
    const known = [...ingredientNames.options].find(option => option.value.toLowerCase() === name.value.trim().toLowerCase());
    if (known && !unit.value) unit.value = known.dataset.unit;
  });

  const remove = document.createElement("button");
  remove.type = "button";
  remove.className = "text-button";
  remove.textContent = "Remove";
  remove.addEventListener("click", () => line.remove());

  line.append(name, quantity, unit, remove);
  return line;
}

function recipeSummary(recipe) {
  if (!recipe.items.length) return "No recipe yet";
  return recipe.items.map(item => `${item.ingredient} ${round(Number(item.quantity))} ${item.unit}`).join(" · ");
}

function recipeRow(recipe) {
  const row = document.createElement("details");
  row.className = "recipe";

  const summary = document.createElement("summary");
  const name = document.createElement("span");
  name.className = "recipe-dish";
  name.textContent = recipe.dish;
  const text = document.createElement("span");
  text.className = "recipe-summary";
  text.textContent = recipeSummary(recipe);
  summary.append(name, text);

  const editor = document.createElement("div");
  editor.className = "recipe-editor";
  const lines = document.createElement("div");
  lines.className = "recipe-lines";
  const fill = items => lines.replaceChildren(...(items.length ? items : [undefined]).map(item => ingredientLine(recipe.dish, item)));
  fill(recipe.items);

  const actions = document.createElement("div");
  actions.className = "recipe-actions";
  const add = document.createElement("button");
  add.type = "button";
  add.className = "text-button";
  add.textContent = "Add ingredient";
  add.addEventListener("click", () => {
    const line = ingredientLine(recipe.dish);
    lines.append(line);
    line.querySelector("input").focus();
  });
  const save = document.createElement("button");
  save.type = "button";
  save.className = "secondary-button";
  save.textContent = "Save recipe";
  save.setAttribute("aria-label", `Save the recipe for ${recipe.dish}`);

  save.addEventListener("click", async () => {
    const items = [];
    for (const line of lines.querySelectorAll(".recipe-line")) {
      const [ingredient, quantity, unit] = [...line.querySelectorAll("input")].map(input => input.value.trim());
      if (!ingredient && !quantity && !unit) continue;
      if (!ingredient || !(Number(quantity) > 0) || !unit) {
        showStatus(`Each ${recipe.dish} line needs an ingredient, an amount above 0 and a unit.`, "error", recipeStatus);
        return;
      }
      items.push({ ingredient, quantity: Number(quantity), unit });
    }

    save.disabled = true;
    save.textContent = "Saving…";
    try {
      const saved = await apiRequest(`/api/dishes/${recipe.dishId}/recipe`, { method: "PUT", body: JSON.stringify({ items }) });
      recipe.items = saved.items;
      text.textContent = recipeSummary(saved);
      fill(saved.items);
      showStatus(`Saved the recipe for ${recipe.dish}.`, "success", recipeStatus);
      loadIngredients();
      loadPrepList();
    } catch (error) {
      showStatus(error.message || "The recipe could not be saved.", "error", recipeStatus);
    } finally {
      save.disabled = false;
      save.textContent = "Save recipe";
    }
  });

  actions.append(add, save);
  editor.append(lines, actions);
  row.append(summary, editor);
  return row;
}

async function loadRecipes() {
  try {
    const recipes = await apiRequest("/api/recipes");
    recipeList.replaceChildren(...(recipes.length
      ? recipes.map(recipeRow)
      : [emptyState("Your dishes appear here once you import sales or record them with the assistant.")]));
  } catch (error) {
    if (error.status !== 401) showStatus("Recipes could not be loaded.", "error", recipeStatus);
  }
}

// One ingredient's yield and whether it's bought for each day or kept in stock.
function buyingRow(ingredient) {
  const row = document.createElement("div");
  row.className = "buying-row";

  const label = document.createElement("div");
  label.className = "buying-name";
  const name = document.createElement("span");
  name.className = "recipe-dish";
  name.textContent = `${ingredient.name} (${ingredient.unit})`;
  label.append(name);
  if (ingredient.defaultYieldBasis) {
    const note = document.createElement("span");
    note.className = "prep-caption";
    note.textContent = `Usually ${round(Number(ingredient.defaultYieldPercent))}%: ${ingredient.defaultYieldBasis}`;
    label.append(note);
  }

  const yieldField = document.createElement("label");
  yieldField.className = "buying-yield";
  const yieldInput = document.createElement("input");
  yieldInput.className = "input";
  yieldInput.type = "number";
  yieldInput.min = "1";
  yieldInput.max = "500";
  yieldInput.step = "0.1";
  yieldInput.inputMode = "decimal";
  yieldInput.value = round(Number(ingredient.yieldPercent)).replace(/,/g, "");
  yieldInput.setAttribute("aria-label", `Yield of ${ingredient.name}, in percent`);
  const percent = document.createElement("span");
  percent.textContent = "% yield";
  percent.setAttribute("aria-hidden", "true");
  yieldField.append(yieldInput, percent);

  const bought = document.createElement("div");
  bought.className = "select-wrap";
  const boughtSelect = document.createElement("select");
  boughtSelect.className = "input";
  boughtSelect.setAttribute("aria-label", `How ${ingredient.name} is bought`);
  [["true", "Bought each day"], ["false", "Kept in stock"]].forEach(([value, text]) => {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = text;
    boughtSelect.append(option);
  });
  boughtSelect.value = String(ingredient.perishable);
  bought.append(boughtSelect);

  const actions = document.createElement("div");
  actions.className = "buying-actions";
  const save = document.createElement("button");
  save.type = "button";
  save.className = "secondary-button";
  save.textContent = "Save";
  save.setAttribute("aria-label", `Save ${ingredient.name}`);
  const reset = document.createElement("button");
  reset.type = "button";
  reset.className = "text-button";
  reset.textContent = "Use defaults";
  reset.hidden = !ingredient.customised;

  const send = async body => {
    save.disabled = true;
    try {
      const saved = await apiRequest(`/api/ingredients/${ingredient.id}`, { method: "PUT", body: JSON.stringify(body) });
      row.replaceWith(buyingRow(saved));
      showStatus(`Saved ${ingredient.name}.`, "success", buyingStatus);
      loadPrepList();
    } catch (error) {
      save.disabled = false;
      showStatus(error.message || `${ingredient.name} could not be saved.`, "error", buyingStatus);
    }
  };
  save.addEventListener("click", () => {
    const value = Number(yieldInput.value);
    if (!(value > 0 && value <= 500)) {
      showStatus(`${ingredient.name}'s yield needs to be above 0 and at most 500%.`, "error", buyingStatus);
      return;
    }
    send({ yieldPercent: value, perishable: boughtSelect.value === "true" });
  });
  reset.addEventListener("click", () => send({ yieldPercent: null, perishable: null }));

  actions.append(save, reset);
  row.append(label, yieldField, bought, actions);
  return row;
}

async function loadIngredients() {
  const request = ++ingredientsRequest;
  try {
    const ingredients = await apiRequest("/api/ingredients");
    if (request !== ingredientsRequest) return;
    stockIngredients = ingredients;
    if (shownList) {
      renderPrepList(shownList);
    } else {
      renderInventory();
    }
    ingredientNames.replaceChildren(...ingredients.map(ingredient => {
      const option = document.createElement("option");
      option.value = ingredient.name;
      option.dataset.unit = ingredient.unit;
      return option;
    }));
    buyingList.replaceChildren(...(ingredients.length
      ? ingredients.map(buyingRow)
      : [emptyState("Ingredients appear here once you add a recipe.")]));
  } catch (error) {
    if (request !== ingredientsRequest) return;
    stockIngredients = null;
    renderInventory();
    showStatus("Stock could not be loaded.", "error", inventoryStatus);
    if (error.status !== 401) buyingList.replaceChildren(emptyState("Ingredients could not be loaded."));
  }
}

function inventoryPlan(list, item, stock) {
  if (!list || list.closed || list.date !== addDays(today, 1) || !item ||
      stock?.stockCountedOn !== today || stock.stockQuantity == null) return null;

  const counted = Number(stock.stockQuantity);
  const expired = stock.stockExpiresOn && stock.stockExpiresOn < list.date;
  const usable = expired ? 0 : counted;
  const requirement = Number(item.toBuy);
  const expectedUse = Number(item.expectedRawUse);
  const purchase = Math.max(0, requirement - usable);
  return {
    usable,
    purchase,
    reduction: requirement - purchase,
    excess: Math.max(0, usable - expectedUse),
    expired: expired ? counted : 0
  };
}

function stockField(label, type, value) {
  const field = document.createElement("label");
  field.textContent = label;
  const input = document.createElement("input");
  input.type = type;
  input.className = "input";
  input.value = value ?? "";
  if (type === "number") {
    input.min = "0";
    input.max = "999999999.999";
    input.step = "0.001";
    input.inputMode = "decimal";
  }
  field.append(input);
  return { field, input };
}

function renderInventory() {
  if (!stockIngredients?.length) {
    inventoryList.replaceChildren(emptyState(stockIngredients
      ? "Add a recipe to start counting stock."
      : "Stock counts are unavailable."));
    return;
  }

  const list = shownList;
  const planningTomorrow = list && !list.closed && list.date === addDays(today, 1);
  const incomplete = planningTomorrow && (list.skipped.length || list.dishes.some(dish => !dish.hasRecipe));
  const notes = [];
  if (!planningTomorrow) notes.push(emptyState("Select tomorrow’s open prep day to see purchase recommendations."));
  if (incomplete) notes.push(emptyState("Some dishes have no recipe or forecast. The ingredient totals may be low."));

  inventoryList.replaceChildren(...notes, ...stockIngredients.map(stock => {
    const row = document.createElement("details");
    row.className = "recipe";
    const summary = document.createElement("summary");
    summary.className = "recipe-dish";
    summary.textContent = `${stock.name} (${stock.unit})`;
    row.append(summary);

    const item = planningTomorrow && list.ingredients.find(entry =>
      entry.ingredient === stock.name && entry.unit === stock.unit);
    const plan = inventoryPlan(list, item, stock);
    if (plan) {
      summary.textContent =
        `${stock.name} (${stock.unit}) · Buy ${formatAmount(plan.purchase, stock.unit)}`;
    } else if (item) {
      summary.textContent = `${stock.name} (${stock.unit}) · Stock unknown`;
    }
    if (item && !plan) {
      row.append(listRow("Stock unknown", formatAmount(item.toBuy, item.unit),
        "Gross prep requirement. Save a fresh count to estimate what to buy."));
    }
    if (plan) {
      const amount = value => formatAmount(value, stock.unit);
    row.append(listRow("Suggested purchase", amount(plan.purchase),
      `${amount(item.toBuy)} needed − ${amount(plan.usable)} usable stock = ${amount(plan.purchase)} to buy`));
      if (plan.expired > 0) {
        row.append(listRow("Stock excluded", amount(plan.expired),
          `Last usable ${formatDay(stock.stockExpiresOn)}. Prep is ${formatDay(list.date)}.`));
      }
      const expected = amount(Number(item.expectedRawUse));
      const expiringSoon =
        stock.stockExpiresOn && stock.stockExpiresOn <= addDays(list.date, 1);

      const advice = plan.excess > 0 && expiringSoon && !incomplete
        ? ` Use soon. Consider featuring ${item.dishes.join(", ")}.`
        : "";

      row.append(listRow(
        "Usable stock above expected demand",
        amount(plan.excess),
        `${amount(plan.usable)} usable raw stock · ${expected} raw expected to be used.${advice}`
      ));
      const review = stockField(`Purchase to share (${stock.unit})`, "number",
        purchaseOverrides.get(stock.id) ?? Math.round(plan.purchase * 1000) / 1000);
      review.input.classList.add("review-purchase");
      review.input.addEventListener("input", () => {
        if (review.input.value === "") purchaseOverrides.delete(stock.id);
        else purchaseOverrides.set(stock.id, review.input.valueAsNumber);
      });
      row.append(review.field);
    }

    const form = document.createElement("form");
    form.className = "recipe-editor";
    const quantity = stockField(`Raw count (${stock.unit})`, "number", stock.stockQuantity);
    const expiry = stockField("Expiry date (optional)", "date", stock.stockExpiresOn);
    const save = document.createElement("button");
    save.className = "secondary-button";
    save.textContent = "Save stock count";
    const counted = stock.stockCountedOn ? `Last counted ${stock.stockCountedOn}. ` : "";
    form.append(quantity.field, expiry.field, save,
      emptyState(`${counted}Save a blank quantity to clear this count.`));
    form.addEventListener("submit", async event => {
      event.preventDefault();
      save.disabled = true;
      try {
        await apiRequest(`/api/ingredients/${stock.id}/stock`, {
          method: "PUT",
          body: JSON.stringify({
            unit: stock.unit,
            quantity: quantity.input.value === "" ? null : quantity.input.valueAsNumber,
            expiresOn: expiry.input.value || null
          })
        });
        purchaseOverrides.delete(stock.id);
        showStatus(`Saved stock for ${stock.name}.`, "success", inventoryStatus);
        await loadIngredients();
      } catch (error) {
        showStatus(error.message || "Stock could not be saved.", "error", inventoryStatus);
      } finally {
        save.disabled = false;
      }
    });
    row.append(form);
    return row;
  }));
}

const today = new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Singapore" });
prepDate.min = today;
prepDate.max = addDays(today, MAX_DAYS_AHEAD);
prepDate.value = addDays(today, 1);
try {
  prepBuffer.value = localStorage.getItem(BUFFER_KEY) ?? "";
} catch (error) {
  // Private browsing: start from the default.
}

prepDate.addEventListener("change", () => {
  if (prepDate.value) loadPrepList();
});
prepBuffer.addEventListener("change", () => {
  try {
    localStorage.setItem(BUFFER_KEY, prepBuffer.value);
  } catch (error) {
    // Not remembered; still works for this visit.
  }
  loadPrepList();
});
shareButton.addEventListener("click", shareList);
window.addEventListener("chicky:sales-saved", loadPrepList);

loadPrepList();
loadRecipes();
loadIngredients();
