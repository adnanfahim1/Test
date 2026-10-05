// Sends a "tick" to the Studio page once a second. Running this in a worker keeps the
// queue moving even when the tab is in the background, where normal timers are slowed.
setInterval(() => postMessage('tick'), 1000);
