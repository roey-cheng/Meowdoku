function createClickResolver({
    onSingle,
    onDouble,
    delay = 230,
    setTimer = setTimeout,
    clearTimer = clearTimeout
}) {
    let pendingClick = null;

    return event => {
        if (event.detail === 0) return;

        if (pendingClick !== null) {
            clearTimer(pendingClick);
            pendingClick = null;
            onDouble();
            return;
        }

        pendingClick = setTimer(() => {
            pendingClick = null;
            onSingle();
        }, delay);
    };
}

function handleCellKey(event, onMark, onGuess) {
    if (event.key === " ") {
        event.preventDefault();
        onMark();
    } else if (event.key === "Enter") {
        event.preventDefault();
        onGuess();
    }
}

function setCellMarked(button, marked, label) {
    button.innerHTML = marked
        ? '<span class="mark-token" aria-hidden="true">×</span>'
        : "";
    button.setAttribute("aria-label", label);
}

function livesLabel(lives) {
    return `${lives} wrong guess${lives === 1 ? "" : "es"} remaining`;
}

createClickResolver.setCellMarked = setCellMarked;
createClickResolver.handleCellKey = handleCellKey;
createClickResolver.livesLabel = livesLabel;
if (typeof module !== "undefined") module.exports = createClickResolver;

if (typeof document !== "undefined") {
const setupScreen = document.querySelector("#setup-screen");
const gameScreen = document.querySelector("#game-screen");
const boardElement = document.querySelector("#board");
const sizeSelect = document.querySelector("#board-size");
const newGameButton = document.querySelector("#new-game");
const setupMessage = document.querySelector("#setup-message");
const restartButton = document.querySelector("#restart");
const showRulesButton = document.querySelector("#show-rules");
const rulesDialog = document.querySelector("#rules-dialog");
const messageElement = document.querySelector("#message");
const scoreElement = document.querySelector("#score");
const guessesElement = document.querySelector("#guesses");
const livesElement = document.querySelector("#lives");
const heartElements = [...livesElement.querySelectorAll(".pixel-heart")];
const completionCard = document.querySelector("#completion");
const completionKicker = document.querySelector("#completion-kicker");
const completionTitle = document.querySelector("#completion-title");
const completionSummary = document.querySelector("#completion-summary");

let game = null;
let busy = false;
const localMarks = new Set();

newGameButton.addEventListener("click", startNewGame);
restartButton.addEventListener("click", showSetup);
showRulesButton.addEventListener("click", () => rulesDialog.showModal());
restoreExistingGame();

async function startNewGame() {
    if (busy) return;
    setBusy(true);
    setupMessage.textContent = "";

    try {
        game = await requestJson(`/api/game?size=${encodeURIComponent(sizeSelect.value)}`, {
            method: "POST"
        });
        localMarks.clear();
        setupScreen.hidden = true;
        gameScreen.hidden = false;
        renderGame();
    } catch (error) {
        setupMessage.textContent = error.message;
    } finally {
        setBusy(false);
    }
}

function showSetup() {
    game = null;
    completionCard.hidden = true;
    gameScreen.hidden = true;
    setupScreen.hidden = false;
    sizeSelect.focus();
}

async function restoreExistingGame() {
    setBusy(true);
    setupMessage.textContent = "";
    try {
        const response = await fetch("/api/game", {method: "GET"});
        if (!response.ok) {
            showSetup();
            return;
        }
        game = await response.json();
        localMarks.clear();
        setupScreen.hidden = true;
        gameScreen.hidden = false;
        renderGame();
    } catch (error) {
        showSetup();
    } finally {
        setBusy(false);
    }
}

function renderGame(focusRequest = null) {
    if (!game) return;

    scoreElement.textContent = game.score;
    guessesElement.textContent = game.guesses;
    heartElements.forEach((heart, index) => {
        heart.classList.toggle("spent", index >= game.livesRemaining);
    });
    livesElement.setAttribute("aria-label", livesLabel(game.livesRemaining));
    showMessage(game.message, game.complete ? "success" : game.lost ? "error" : "");

    boardElement.innerHTML = "";
    boardElement.style.gridTemplateColumns = `repeat(${game.size}, 1fr)`;
    boardElement.style.gridTemplateRows = `repeat(${game.size}, 1fr)`;
    boardElement.setAttribute("aria-label", `${game.size} by ${game.size} Meowdoku board`);

    for (let row = 0; row < game.size; row++) {
        for (let column = 0; column < game.size; column++) {
            boardElement.append(createCell(row, column));
        }
    }

    const gameOver = isGameOver();
    completionCard.hidden = !gameOver;
    completionCard.classList.toggle("lost", game.lost);
    if (gameOver) {
        completionKicker.textContent = game.lost ? "Out of chances" : "Puzzle complete";
        completionTitle.textContent = game.lost ? "Game over!" : "You found every cat!";
        completionSummary.textContent = `Final score: ${game.score} points from ${game.guesses} guesses.`;
        requestAnimationFrame(() => restartButton.focus());
    } else if (focusRequest) {
        requestAnimationFrame(() => restoreBoardFocus(focusRequest));
    }
}

function createCell(row, column) {
    const cell = game.board[row][column];
    const button = document.createElement("button");
    button.type = "button";
    button.className = `cell region-${cell.regionId % 9}`;
    button.dataset.row = row;
    button.dataset.column = column;

    if (cell.state === "FOUND_CAT") {
        button.innerHTML = '<span class="cat-token" aria-hidden="true">=^.^=</span>';
        button.disabled = true;
    } else if (cell.state === "WRONG_GUESS") {
        button.innerHTML = '<span class="wrong-token" aria-hidden="true">×</span>';
        button.disabled = true;
    } else if (localMarks.has(cellKey(row, column))) {
        button.innerHTML = '<span class="mark-token" aria-hidden="true">×</span>';
    }

    if (isGameOver()) button.disabled = true;

    button.setAttribute("aria-label", cellLabel(
        row, column, cell, localMarks.has(cellKey(row, column))
    ));
    button.addEventListener("click", createClickResolver({
        onSingle: () => toggleMark(row, column, button),
        onDouble: () => guessCell(row, column)
    }));
    button.addEventListener("keydown", event => handleCellKey(
        event,
        () => toggleMark(row, column, button),
        () => guessCell(row, column)
    ));
    return button;
}

function toggleMark(row, column, button) {
    if (!game || busy || isGameOver()) return;
    if (game.board[row][column].state !== "HIDDEN") return;

    const key = cellKey(row, column);
    if (localMarks.has(key)) localMarks.delete(key);
    else localMarks.add(key);
    setCellMarked(
        button,
        localMarks.has(key),
        cellLabel(row, column, game.board[row][column], localMarks.has(key))
    );
}

async function guessCell(row, column) {
    if (!game || busy || isGameOver()) return;
    if (game.board[row][column].state !== "HIDDEN") return;

    setBusy(true);
    try {
        game = await requestJson(`/api/guess?row=${row}&column=${column}`, {
            method: "POST"
        });
        localMarks.delete(cellKey(row, column));
        renderGame({ row, column });
    } catch (error) {
        showMessage(error.message, "error");
    } finally {
        setBusy(false);
    }
}

function cellKey(row, column) {
    return `${row},${column}`;
}

function isGameOver() {
    return game.complete || game.lost;
}

function restoreBoardFocus({ row, column }) {
    const changedCell = boardElement.querySelector(
        `[data-row="${row}"][data-column="${column}"]`
    );
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

function setBusy(nextBusy) {
    busy = nextBusy;
    newGameButton.disabled = busy;
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
}
