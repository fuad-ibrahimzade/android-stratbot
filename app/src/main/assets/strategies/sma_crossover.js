// ============================================================
// SMA crossover
// Buys when the fast SMA crosses above the slow SMA, sells when
// it crosses back below.  Position sizing: buyFraction(frac).
//
// Try it as: backtest first, then dry run, then (carefully) live.
// ============================================================

function init(ctx) {
  ctx.log(
    "SMA crossover  fast=" + ctx.param("fast", 20) +
    "  slow=" + ctx.param("slow", 60) +
    "  size=" + (ctx.param("size", 0.95) * 100).toFixed(0) + "% of cash"
  );
}

function onBar(ctx, bar) {
  var fast = Number(ctx.param("fast", 20));
  var slow = Number(ctx.param("slow", 60));
  var size = Number(ctx.param("size", 0.95));

  var c = ctx.closes(400);
  if (c.length < slow + 2) return;          // need enough history

  var up = sma(c, fast) > sma(c, slow);
  var wasUp = sma(slice(c, 1), fast) > sma(slice(c, 1), slow);

  if (!wasUp && up) {
    if (ctx.position <= 0) {
      ctx.buyFraction(size);
      ctx.log("GOLDEN cross @ " + fmt(bar.c) + " -> long");
    }
  } else if (wasUp && !up) {
    if (ctx.position > 0) {
      ctx.close();
      ctx.log("DEATH cross @ " + fmt(bar.c) + " -> flat");
    }
  }
}

function fmt(x) { return Number(x).toFixed(2); }