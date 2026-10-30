// ============================================================
// RSI mean reversion
// Buys when RSI(14) drops below 30 (oversold), exits when RSI
// climbs above 55 or a stop/target band is hit.
//
// Works best on a liquid pair in a ranging market.
// ============================================================

function init(ctx) {
  ctx.log("RSI reversion ready");
}

function onBar(ctx, bar) {
  var period = Number(ctx.param("rsiPeriod", 14));
  var low = Number(ctx.param("oversold", 30));
  var high = Number(ctx.param("overbought", 55));
  var stopPct = Number(ctx.param("stopPct", 3.0));
  var takePct = Number(ctx.param("takePct", 5.0));
  var size = Number(ctx.param("size", 0.9));

  var c = ctx.closes(200);
  if (c.length < period + 3) return;

  var r = rsi(c, period);

  if (ctx.position > 0) {
    var change = ((bar.c - ctx.entryPrice) / ctx.entryPrice) * 100.0;
    if (change <= -stopPct || change >= takePct || r > high) {
      ctx.log("exit @ " + fmt(bar.c) + " rsi=" + r.toFixed(1) + " pnl=" + change.toFixed(2) + "%");
      ctx.close();
    }
    return;
  }

  var prev = rsi(slice(c, 1), period);
  if (prev >= low && r < low) {
    ctx.buyFraction(size);
    ctx.log("oversold entry @ " + fmt(bar.c) + " rsi=" + r.toFixed(1));
  }
}

function fmt(x) { return Number(x).toFixed(2); }