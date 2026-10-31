// Prep list page: portions and ingredients to prepare for a day, and each dish's recipe
// (issue #45). Needs js/api.js.

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

// The backend works out prep lists from today up to this many days ahead (Singapore time).
const MAX_DAYS_AHEAD = 7;
const BUFFER_KEY = "chicky-prep-buffer";

let shownList = null;

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
      `Forecast ${dish.expected} (likely ${dish.low}–${dish.high}) · ${dish.basis}`
    )), ...skipped);
  }

  if (!list.ingredients.length) {
    ingredientList.replaceChildren(emptyState("Add recipes below to see how much of each ingredient you need."));
  } else {
    ingredientList.replaceChildren(...list.ingredients.map(item => listRow(
      item.ingredient, formatAmount(item.quantity, item.unit), `For ${item.dishes.join(", ")}`
    )));
  }

  const withoutRecipe = list.dishes.filter(dish => !dish.hasRecipe).map(dish => dish.dish);
  if (list.ingredients.length && withoutRecipe.length) {
    missingRecipes.textContent = `Not counted, no recipe yet: ${withoutRecipe.join(", ")}.`;
  }
}

// Plain text that reads well in WhatsApp.
function prepListText(list) {
  const lines = [`Prep for ${formatDay(list.date)}`, ""];
  list.dishes.forEach(dish => lines.push(`${dish.dish}: ${dish.portions}`));
  if (list.ingredients.length) {
    lines.push("", "Ingredients");
    list.ingredients.forEach(item => lines.push(`${item.ingredient}: ${formatAmount(item.quantity, item.unit)}`));
  }
  return lines.join("\n");
}

async function loadPrepList() {
  shareButton.disabled = true;
  showStatus("", null, prepStatus);
  portionList.replaceChildren(emptyState("Working out the forecasts…"));
  ingredientList.replaceChildren(emptyState("Loading…"));
  const buffer = prepBuffer.value === "" ? "" : `&bufferPercent=${prepBuffer.value}`;
  try {
    renderPrepList(await apiRequest(`/api/prep-list?date=${prepDate.value}${buffer}`));
  } catch (error) {
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
  quantity.placeholder = "Amount";
  quantity.value = item.quantity;
  quantity.setAttribute("aria-label", `Amount per portion of ${dishName}`);

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
      loadIngredientNames();
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

async function loadIngredientNames() {
  try {
    const ingredients = await apiRequest("/api/ingredients");
    ingredientNames.replaceChildren(...ingredients.map(ingredient => {
      const option = document.createElement("option");
      option.value = ingredient.name;
      option.dataset.unit = ingredient.unit;
      return option;
    }));
  } catch (error) {
    // Suggestions only; the editor works without them.
  }
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
loadIngredientNames();
