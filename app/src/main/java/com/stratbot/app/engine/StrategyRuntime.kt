package com.stratbot.app.engine

import android.util.Log
import com.stratbot.app.model.Candle
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** What a strategy may touch. Implemented by backtest / market sessions. */
interface HostApi {
    val exchangeId: String
    val symbol: String
    val timeframe: String
    val modeName: String

    fun now(): Long
    fun lastPrice(): Double
    fun bid(): Double
    fun ask(): Double
    fun position(): Double
    fun entryPrice(): Double
    fun cash(): Double
    fun equity(): Double
    fun openOrders(): Int

    fun buy(qty: Double): Boolean
    fun sell(qty: Double): Boolean
    fun buyFraction(frac: Double): Boolean
    fun sellFraction(frac: Double): Boolean
    fun closePosition(): Boolean
    fun limitBuy(price: Double, qty: Double): String?
    fun limitSell(price: Double, qty: Double): String?
    fun cancel(orderId: String): Boolean

    fun bars(n: Int): Array<Candle>
    fun opens(n: Int): DoubleArray
    fun highs(n: Int): DoubleArray
    fun lows(n: Int): DoubleArray
    fun closes(n: Int): DoubleArray
    fun volumes(n: Int): DoubleArray

    fun param(key: String, def: Any?): Any?
    fun log(msg: String)
    fun requestStop()
}

/** The `ctx` object handed to strategies. */
class StrategyContext(private val host: HostApi) {
    val exchange: String get() = host.exchangeId
    val symbol: String get() = host.symbol
    val timeframe: String get() = host.timeframe
    val mode: String get() = host.modeName

    val now: Long get() = host.now()
    val price: Double get() = host.lastPrice()
    val bid: Double get() = host.bid()
    val ask: Double get() = host.ask()
    val position: Double get() = host.position()
    val entryPrice: Double get() = host.entryPrice()
    val cash: Double get() = host.cash()
    val equity: Double get() = host.equity()
    val openOrders: Int get() = host.openOrders()

    fun buy(qty: Double): Boolean = host.buy(qty)
    fun sell(qty: Double): Boolean = host.sell(qty)
    fun buyFraction(frac: Double): Boolean = host.buyFraction(frac)
    fun sellFraction(frac: Double): Boolean = host.sellFraction(frac)
    fun close(): Boolean = host.closePosition()
    fun closeAll(): Boolean = host.closePosition()
    fun limitBuy(price: Double, qty: Double): String? = host.limitBuy(price, qty)
    fun limitSell(price: Double, qty: Double): String? = host.limitSell(price, qty)
    fun cancel(orderId: String): Boolean = host.cancel(orderId)

    fun bars(n: Int): Array<Candle> = host.bars(n)
    fun opens(n: Int): DoubleArray = host.opens(n)
    fun highs(n: Int): DoubleArray = host.highs(n)
    fun lows(n: Int): DoubleArray = host.lows(n)
    fun closes(n: Int): DoubleArray = host.closes(n)
    fun volumes(n: Int): DoubleArray = host.volumes(n)

    fun param(key: String, def: Any?): Any? = host.param(key, def)
    fun log(msg: Any?) = host.log(msg?.toString() ?: "null")
    fun stop() = host.requestStop()
}

/**
 * Rhino in interpreter mode (optimizationLevel = -1) — required for dex on Android.
 * All calls stay on the owning session's engine thread.
 */
class StrategyRuntime(private val host: HostApi) {
    private var cx: Context? = null
    private var scope: Scriptable? = null
    private var entered = false
    val ctx = StrategyContext(host)

    fun init() {
        if (entered) return
        val c = Context.enter()
        entered = true
        c.optimizationLevel = -1
        runCatching { c.setLanguageVersion(Context.VERSION_ES6) }
        val s = c.initStandardObjects()
        ScriptableObject.putProperty(s, "ctx", Context.javaToJS(ctx, s))
        c.evaluateString(s, PRELUDE, "stratbot:prelude", 1, null)
        cx = c
        scope = s
    }

    /** may throw RhinoException (user code errors) */
    fun load(code: String) {
        val c = cx ?: error("runtime not initialised")
        c.evaluateString(scope, code, "strategy.js", 1, null)
    }

    fun has(fn: String): Boolean {
        val s = scope ?: return false
        return ScriptableObject.getProperty(s, fn) is Function
    }

    fun call(fn: String, vararg args: Any?): Any? {
        val c = cx ?: return null
        val s = scope ?: return null
        val f = ScriptableObject.getProperty(s, fn) as? Function ?: return null
        val jsArgs = args.map { Context.javaToJS(it, s) }.toTypedArray()
        return f.call(c, s, s, jsArgs)
    }

    fun eval(expr: String): Any? = try {
        cx?.evaluateString(scope, expr, "expr", 1, null)
    } catch (t: Throwable) {
        Log.w(TAG, "eval failed: ${t.message}")
        null
    }

    fun destroy() {
        try {
            if (entered) Context.exit()
        } catch (_: Throwable) {
        }
        entered = false
        cx = null
        scope = null
    }

    companion object {
        private const val TAG = "StrategyRuntime"

        val PRELUDE: String = """
        function len(a){ return (a===undefined||a===null) ? 0 : a.length; }
        function last(a){ return (a===undefined||a===null||a.length===0) ? NaN : a[a.length-1]; }
        function slice(a, from, to){
          if (a===undefined||a===null) return [];
          var st = (from===undefined||from===null)?0:from;
          var en = (to===undefined||to===null)?a.length:to;
          if (st<0) st = 0;
          var out = [];
          for (var i=st;i<en;i++) out.push(a[i]);
          return out;
        }
        function shift(a){ return slice(a,1); }
        function sma(a,p){
          p = Math.round(Number(p));
          if (!a || a.length < p) return NaN;
          var s = 0;
          for (var i=a.length-p;i<a.length;i++) s += Number(a[i]);
          return s/p;
        }
        function ema(a,p){
          p = Math.round(Number(p));
          if (!a || a.length < p) return NaN;
          var k = 2/(p+1), e = 0, start = a.length-p, i;
          for (i=start;i<a.length;i++) e += Number(a[i]);
          e /= p;
          for (i=start+p;i<a.length;i++) e = Number(a[i])*k + e*(1-k);
          return e;
        }
        function rsi(a,p){
          p = Math.round(Number(p));
          if (!a || a.length <= p) return NaN;
          var g=0,l=0,n=a.length,i;
          for (i=n-p;i<n;i++){ var d = Number(a[i]) - Number(a[i-1]); if (d>=0) g+=d; else l-=(-d); }
          g/=p; l/=p;
          if (l===0) return 100;
          return 100 - 100/(1 + g/l);
        }
        function stdev(a,p){
          p = Math.round(Number(p));
          if (!a || a.length < p || p<2) return NaN;
          var m=0, start=a.length-p, i;
          for (i=start;i<a.length;i++) m += Number(a[i]);
          m /= p;
          var v=0;
          for (i=start;i<a.length;i++){ var d=Number(a[i])-m; v += d*d; }
          return Math.sqrt(v/(p-1));
        }
        function highest(a,n){
          if (!a || a.length===0) return NaN;
          var m = Number(a[a.length-1]);
          for (var i=Math.max(0,a.length-n);i<a.length;i++) if (Number(a[i])>m) m = Number(a[i]);
          return m;
        }
        function lowest(a,n){
          if (!a || a.length===0) return NaN;
          var m = Number(a[a.length-1]);
          for (var i=Math.max(0,a.length-n);i<a.length;i++) if (Number(a[i])<m) m = Number(a[i]);
          return m;
        }
        function atr(h,l,c,p){
          p = Math.round(Number(p));
          if (!h || !l || !c || c.length < p+1) return NaN;
          var s=0, n=c.length;
          for (var i=n-p;i<n;i++){
            var hl = Number(h[i]) - Number(l[i]);
            var hc = Math.abs(Number(h[i]) - Number(c[i-1]));
            var lc = Math.abs(Number(l[i]) - Number(c[i-1]));
            s += Math.max(hl,hc,lc);
          }
          return s/p;
        }
        function __emaSeries(a,p){
          var out = new Array(a.length);
          if (a.length < p) { for (var i=0;i<out.length;i++) out[i]=NaN; return out; }
          var k=2/(p+1), e=0, i;
          for (i=0;i<p;i++) e += Number(a[i]);
          e/=p; out[p-1]=e;
          for (i=p;i<a.length;i++){ e = Number(a[i])*k + e*(1-k); out[i]=e; }
          return out;
        }
        function macd(a,fast,slow,sig){
          fast=Math.round(Number(fast)); slow=Math.round(Number(slow)); sig=Math.round(Number(sig));
          if (!a || a.length < slow+sig) return [NaN,NaN,NaN];
          var f=__emaSeries(a,fast), sy=__emaSeries(a,slow);
          var line=[], i;
          for (i=0;i<a.length;i++) line.push(f[i]-sy[i]);
          var st=Math.max(0,line.length-sig);
          var tail=[];
          for (i=st;i<line.length;i++) tail.push(line[i]);
          var signal=ema(tail, sig);
          var cur=line[line.length-1];
          return [cur, signal, cur-signal];
        }
        var PI = Math.PI;
        """
    }
}