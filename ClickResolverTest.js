const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const modulePath = path.join(__dirname, "web", "app.js");
assert.ok(fs.existsSync(modulePath), "click resolver module should exist");
const createClickResolver = require(modulePath);
const setCellMarked = createClickResolver.setCellMarked;
const handleCellKey = createClickResolver.handleCellKey;
const livesLabel = createClickResolver.livesLabel;
const recoverGuessState = createClickResolver.recoverGuessState;

assert.equal(typeof setCellMarked, "function",
    "cell marks should be updated without rebuilding the board");
assert.equal(typeof handleCellKey, "function",
    "keyboard users should be able to mark and guess explicitly");
assert.equal(typeof recoverGuessState, "function",
    "failed guesses should recover the latest server state");
assert.equal(livesLabel(3), "3 wrong guesses remaining");
assert.equal(livesLabel(1), "1 wrong guess remaining");

function fakeTimer() {
    let callback = null;
    return {
        set(nextCallback) {
            callback = nextCallback;
            return 1;
        },
        clear() {
            callback = null;
        },
        run() {
            const nextCallback = callback;
            callback = null;
            nextCallback?.();
        },
        hasPending() {
            return callback !== null;
        }
    };
}

function resolverFixture() {
    const timer = fakeTimer();
    const calls = { single: 0, double: 0 };
    const resolveClick = createClickResolver({
        onSingle: () => calls.single++,
        onDouble: () => calls.double++,
        setTimer: callback => timer.set(callback),
        clearTimer: () => timer.clear()
    });
    return { timer, calls, resolveClick };
}

{
    const { timer, calls, resolveClick } = resolverFixture();
    resolveClick({ detail: 1 });
    assert.equal(calls.single, 0, "single click should wait for a possible second click");
    timer.run();
    assert.deepEqual(calls, { single: 1, double: 0 });
}

{
    const { timer, calls, resolveClick } = resolverFixture();
    resolveClick({ detail: 0 });
    timer.run();
    assert.deepEqual(calls, { single: 0, double: 0 },
        "synthetic keyboard clicks should not duplicate key handling");
}

{
    const calls = { mark: 0, guess: 0, prevented: 0 };
    const event = key => ({ key, preventDefault: () => calls.prevented++ });
    handleCellKey(event(" "), () => calls.mark++, () => calls.guess++);
    handleCellKey(event("Enter"), () => calls.mark++, () => calls.guess++);
    assert.deepEqual(calls, { mark: 1, guess: 1, prevented: 2 });
}

{
    const { timer, calls, resolveClick } = resolverFixture();
    resolveClick({ detail: 1 });
    resolveClick({ detail: 2 });
    assert.equal(timer.hasPending(), false, "double click should cancel the pending mark");
    timer.run();
    assert.deepEqual(calls, { single: 0, double: 1 });
}

{
    const { timer, calls, resolveClick } = resolverFixture();
    resolveClick({ detail: 1 });
    resolveClick({ detail: 1 });
    assert.equal(timer.hasPending(), false,
        "two mobile taps should cancel the pending mark");
    timer.run();
    assert.deepEqual(calls, { single: 0, double: 1 },
        "two mobile taps should guess even when both click details are 1");
}

{
    const { timer, calls, resolveClick } = resolverFixture();
    resolveClick({ detail: 1 });
    timer.run();
    resolveClick({ detail: 1 });
    timer.run();
    assert.deepEqual(calls, { single: 2, double: 0 },
        "a later single click should be allowed to toggle the mark off");
}

{
    const attributes = {};
    const button = {
        innerHTML: "",
        setAttribute: (name, value) => attributes[name] = value
    };

    setCellMarked(button, true, "Row 1, column 1, marked empty");
    assert.match(button.innerHTML, /mark-token/);
    assert.equal(attributes["aria-label"], "Row 1, column 1, marked empty");

    setCellMarked(button, false, "Row 1, column 1, hidden");
    assert.equal(button.innerHTML, "");
    assert.equal(attributes["aria-label"], "Row 1, column 1, hidden");
}

function recoveredGame(cellState) {
    const board = Array.from({ length: 4 }, (_, row) =>
        Array.from({ length: 4 }, (_, column) => ({
            regionId: (row + column) % 4,
            state: "HIDDEN"
        }))
    );
    board[1][2].state = cellState;
    return {
        size: 4,
        score: cellState === "HIDDEN" ? 0 : -1,
        guesses: cellState === "HIDDEN" ? 0 : 1,
        catsFound: 1,
        livesRemaining: cellState === "HIDDEN" ? 4 : 3,
        complete: false,
        lost: false,
        message: "Session restored",
        board
    };
}

async function testGuessRecovery() {
    const calls = [];
    const processedGame = recoveredGame("WRONG_GUESS");
    const processed = await recoverGuessState(async (url, options) => {
        calls.push({ url, options });
        return processedGame;
    }, 1, 2);
    assert.deepEqual(calls, [{ url: "/api/game", options: { method: "GET" } }]);
    assert.equal(processed.game, processedGame);
    assert.equal(processed.processed, true,
        "a changed cell means the failed POST reached the server");

    const pending = await recoverGuessState(
        async () => recoveredGame("HIDDEN"), 1, 2
    );
    assert.equal(pending.processed, false,
        "a hidden cell means the player should double-tap again");

    await assert.rejects(
        recoverGuessState(async () => { throw new TypeError("offline"); }, 1, 2),
        /offline/,
        "a failed recovery GET should be handled by the UI"
    );
}

testGuessRecovery()
    .then(() => console.log("ClickResolverTest passed"))
    .catch(error => {
        console.error(error);
        process.exitCode = 1;
    });
