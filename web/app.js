const boardElement = document.querySelector("#board");
const sizeSelect = document.querySelector("#board-size");
const newGameButton = document.querySelector("#new-game");
const playAgainButton = document.querySelector("#play-again");
const modeButtons = [...document.querySelectorAll(".mode-button")];
const modeHelp = document.querySelector("#mode-help");
const messageElement = document.querySelector("#message");
const scoreElement = document.querySelector("#score");
const guessesElement = document.querySelector("#guesses");
const catsFoundElement = document.querySelector("#cats-found");
const puzzleTitle = document.querySelector("#puzzle-title");
const gameBadge = document.querySelector("#game-badge");
const completionCard = document.querySelector("#completion");
const completionSummary = document.querySelector("#completion-summary");

let game = null;
let mode = "guess";
let busy = false;
const localMarks = new Set();

newGameButton.addEventListener("click", startNewGame);
playAgainButton.addEventListener("click", startNewGame);

for (const button of modeButtons) {
    button.addEventListener("click", () => setMode(button.dataset.mode));
}

restoreGame();

async function restoreGame() {
    try {
        const response = await fetch("/api/game");
        if (response.status === 404) return;
        const data = await response.json();
        if (!response.ok) throw new Error(data.error || "Could not restore the game");
        game = data;
        sizeSelect.value = String(game.size);
        renderGame();
    } catch (error) {
        showMessage(error.message, "error");
    }
}

async function startNewGame() {
    if (busy) return;
    setBusy(true);

    try {
        const newGame = await requestJson(`/api/game?size=${encodeURIComponent(sizeSelect.value)}`, {
            method: "POST"
        });
        game = newGame;
        localMarks.clear();
        setMode("guess");
        renderGame();
    } catch (error) {
        showMessage(error.message, "error");
    } finally {
        setBusy(false);
    }
}

function setMode(nextMode) {
    mode = nextMode;
    for (const button of modeButtons) {
        const active = button.dataset.mode === mode;
        button.classList.toggle("active", active);
        button.setAttribute("aria-pressed", String(active));
    }
    modeHelp.textContent = mode === "guess"
        ? "Choose a cell where you think a cat is hiding."
        : "Tap hidden cells to add or remove your own no-cat marks.";
}

function renderGame(focusRequest = null) {
    if (!game) return;

    scoreElement.textContent = game.score;
    guessesElement.textContent = game.guesses;
    catsFoundElement.textContent = `${game.catsFound} / ${game.size}`;
    puzzleTitle.textContent = `${game.size} × ${game.size} colour puzzle`;
    gameBadge.textContent = game.complete ? "Complete" : "In progress";
    gameBadge.className = `game-badge ${game.complete ? "complete" : "active"}`;
    showMessage(game.message, game.complete ? "success" : "");

    boardElement.className = "board";
    boardElement.innerHTML = "";
    boardElement.style.gridTemplateColumns = `repeat(${game.size}, 1fr)`;
    boardElement.setAttribute("aria-label", `${game.size} by ${game.size} Meowdoku board`);

    for (let row = 0; row < game.size; row++) {
        for (let column = 0; column < game.size; column++) {
            boardElement.append(createCell(row, column));
        }
    }

    completionCard.hidden = !game.complete;
    if (game.complete) {
        completionSummary.textContent = `Final score: ${game.score} points from ${game.guesses} guesses.`;
        requestAnimationFrame(() => playAgainButton.focus());
    } else if (focusRequest) {
        requestAnimationFrame(() => restoreBoardFocus(focusRequest));
    }
}

function createCell(row, column) {
    const cell = game.board[row][column];
    const key = cellKey(row, column);
    const button = document.createElement("button");
    button.type = "button";
    button.className = `cell region-${cell.regionId % 9}`;
    button.dataset.row = row;
    button.dataset.column = column;

    addRegionBorders(button, row, column, cell.regionId);

    if (cell.state === "FOUND_CAT") {
        button.innerHTML = '<span class="cat-token" aria-hidden="true">🐱</span>';
        button.disabled = true;
    } else if (cell.state === "WRONG_GUESS") {
        button.innerHTML = '<span class="wrong-token" aria-hidden="true">×</span>';
        button.disabled = true;
    } else if (localMarks.has(key)) {
        button.innerHTML = '<span class="mark-token" aria-hidden="true">×</span>';
    }

    if (game.complete) button.disabled = true;

    button.setAttribute("aria-label", cellLabel(row, column, cell, localMarks.has(key)));
    button.addEventListener("click", () => handleCellClick(row, column));
    return button;
}

function addRegionBorders(button, row, column, regionId) {
    if (row === 0 || game.board[row - 1][column].regionId !== regionId) {
        button.classList.add("region-top");
    }
    if (row === game.size - 1 || game.board[row + 1][column].regionId !== regionId) {
        button.classList.add("region-bottom");
    }
    if (column === 0 || game.board[row][column - 1].regionId !== regionId) {
        button.classList.add("region-left");
    }
    if (column === game.size - 1 || game.board[row][column + 1].regionId !== regionId) {
        button.classList.add("region-right");
    }
}

async function handleCellClick(row, column) {
    if (!game || busy || game.complete) return;
    const cell = game.board[row][column];
    if (cell.state !== "HIDDEN") return;

    const key = cellKey(row, column);
    if (mode === "mark") {
        if (localMarks.has(key)) localMarks.delete(key);
        else localMarks.add(key);
        renderGame({ row, column });
        return;
    }

    localMarks.delete(key);
    setBusy(true);
    try {
        game = await requestJson(`/api/guess?row=${row}&column=${column}`, {
            method: "POST"
        });
        renderGame({
            row,
            column,
            animation: game.board[row][column].state === "FOUND_CAT"
                ? "correct-pop"
                : "wrong-shake"
        });
    } catch (error) {
        showMessage(error.message, "error");
    } finally {
        setBusy(false);
    }
}

function restoreBoardFocus({ row, column, animation }) {
    const changedCell = boardElement.querySelector(
        `[data-row="${row}"][data-column="${column}"]`
    );
    if (changedCell && animation) changedCell.classList.add(animation);

    if (changedCell && !changedCell.disabled) {
        changedCell.focus();
        return;
    }

    const enabledCells = [...boardElement.querySelectorAll(".cell:not(:disabled)")];
    const nextCell = enabledCells.find(cell =>
        Number(cell.dataset.row) > row
        || (Number(cell.dataset.row) === row && Number(cell.dataset.column) > column)
    );
    (nextCell || enabledCells[0])?.focus();
}

function cellLabel(row, column, cell, marked) {
    let state = "hidden";
    if (cell.state === "FOUND_CAT") state = "cat found";
    else if (cell.state === "WRONG_GUESS") state = "wrong guess";
    else if (marked) state = "marked empty";
    return `Row ${row + 1}, column ${column + 1}, colour region ${cell.regionId + 1}, ${state}`;
}

function cellKey(row, column) {
    return `${row},${column}`;
}

function setBusy(nextBusy) {
    busy = nextBusy;
    newGameButton.disabled = busy;
    for (const button of modeButtons) button.disabled = busy;
    boardElement.setAttribute("aria-busy", String(busy));
}

function showMessage(message, type = "") {
    messageElement.textContent = message;
    messageElement.className = `message${type ? ` ${type}` : ""}`;
}

async function requestJson(url, options) {
    const response = await fetch(url, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "Something went wrong");
    return data;
}
