package com.parker.forex.strategies;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import com.dukascopy.api.Configurable;
import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

/**
 * Performance-based strategy that monitors multiple concurrent strategies and opens th next order using the strategy
 * with the best current win percentage.
 * 
 * @since April 2018.
 */
public class TheCreeper implements IStrategy {

    private static final SimpleDateFormat DATE_FORMAT_SHORT = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat DATE_FORMAT_LONG = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS");
    private static final SimpleDateFormat DATE_FORMAT_MONTH = new SimpleDateFormat("MMMMM yyyy");
    
    private static final long MILLIS_IN_YEAR = 31_536_000_000L;
    private static final long MILLIS_IN_DAY = 86_400_000L;
    
    static {
        DATE_FORMAT_SHORT.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_LONG.setTimeZone(TimeZone.getTimeZone("GMT"));
        DATE_FORMAT_MONTH.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    // *****************************************************************************************************************
    // Instance Fields
    // *****************************************************************************************************************
    IContext context;
    int orderCounter;
    
    int consecutiveLossCounter;
    int maxConsecutiveLossCounter;
    
    double lockedProfit;
    double maxProfit;
    double maxDrawDown;
    
    long startTime;
    long endTime;
    double startEquity;
    
    List<InstrumentStrategy> strategies;

    @Configurable(value = "Base Trade Amount")
    public final double baseTradeAmount = 10.0;
    
    @Configurable(value = "Max Trade Amount")
    public final double maxTradeAmount = 500;
    
    @Configurable(value = "Max Concurrent Trades")
    public final int maxConcurrentTrades = 5;
    
    @Configurable(value = "Min Win %")
    public final int minWinPct = 50;
    
    // *****************************************************************************************************************
    // Private Methods
    // *****************************************************************************************************************
    private void init(IContext context) {
        this.context = context;
        
        Set<Instrument> instruments = new HashSet<>();
        
        instruments.add(Instrument.EURUSD);
        instruments.add(Instrument.USDJPY);
        instruments.add(Instrument.GBPUSD);
        
        instruments.add(Instrument.EURJPY);
        instruments.add(Instrument.EURGBP);
        instruments.add(Instrument.GBPJPY);
        
        context.setSubscribedInstruments(instruments);

        strategies  = new ArrayList<>();
        
        for (Instrument instrument : instruments) {
            for (StrategyType strategyType : StrategyType.values()) {
                for (int takeProfitPips = 20; takeProfitPips <= 100; takeProfitPips += 20) {
                    strategies.add(new InstrumentStrategy(instrument, strategyType, takeProfitPips));
                }
            }
        }
        
        log("\nStarted the " + getName() + " strategy using " + strategies.size() + " strategies across " + instruments.size() + " instruments.");
    }
    
    private void checkAndTrade(long time) throws JFException {
        int tradesRequired = maxConcurrentTrades - this.getStrategiesWithOpenTrades().size();
        if (tradesRequired > 0) {
            for (InstrumentStrategy strategy : this.getBestStrategies()) {
                if (tradesRequired > 0 && !this.hasOpenPosition(strategy)) {
                    this.placeOrder(strategy);
                    tradesRequired--;
                }
            }
            
            if (startTime == 0) {
                startTime = time;
                startEquity = context.getAccount().getEquity();
            }
        }
    }
    
    private String getName() {
        return "THE_CREEPER";
    }

    private void log(String label, long time, String message) {
        log(label + " @ " + DATE_FORMAT_LONG.format(new Date(time)) + ": " + message); 
    }
    
    private void log(String message) {
        context.getConsole().getOut().println(message);
    }

    private double round(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
    }
    
    private List<InstrumentStrategy> getStrategies(Instrument instrument) {
        List<InstrumentStrategy> instrumentStrategies = new ArrayList<>();
        for (InstrumentStrategy strategy : strategies) {
            if (strategy.instrument.equals(instrument)) {
                instrumentStrategies.add(strategy);
            }
        }
        return instrumentStrategies;
    }
    
    private List<InstrumentStrategy> getBestStrategies() {
        List<InstrumentStrategy> bestStrategies = new ArrayList<>();
        for (InstrumentStrategy strategy : this.strategies) {
            if (strategy.virtualWinPct() >= this.minWinPct) {
                bestStrategies.add(strategy);
            }
        }
        
        Collections.sort(bestStrategies);
        
        return bestStrategies.subList(0, Math.min(this.maxConcurrentTrades, bestStrategies.size()));
    }
    
    private int getWins() {
        return strategies.stream().mapToInt(s -> s.wins).sum();
    }
    
    private int getLosses() {
        return strategies.stream().mapToInt(s -> s.losses).sum();
    }
    
    private double getTotalProfit() {
        return strategies.stream().mapToDouble(s -> s.profit).sum();
    }
    
    private InstrumentStrategy getStrategy(IOrder order) {
        for (InstrumentStrategy  strategy : strategies) {
            if (order.equals(strategy.order)) {
             return strategy;   
            }
        }
        return null;
    }
    
    private List<InstrumentStrategy> getStrategiesWithOpenTrades() throws JFException {
        List<InstrumentStrategy> openStrategies = new ArrayList<>();
        for (InstrumentStrategy strategy : this.strategies) {
            if (hasOpenPosition(strategy)) {
                openStrategies.add(strategy);
            }
        }
        return openStrategies;
    }
    
    private boolean hasOpenPosition(InstrumentStrategy strategy) throws JFException {
        IOrder order = strategy.order;
        return order != null && !State.CLOSED.equals(order.getState()) && !State.CANCELED.equals(order.getState()); 
    }
    
    private double getLotSize(InstrumentStrategy strategy) throws JFException {
        double tradeAmount = this.baseTradeAmount;
        
        // Increase the trade amount if we are under the locked profit amount
        double totalProfit = this.getTotalProfit();
        if (totalProfit < lockedProfit) {
            tradeAmount = (lockedProfit - totalProfit) / maxConcurrentTrades + baseTradeAmount;
            tradeAmount = Math.min(tradeAmount, maxTradeAmount);
        }
        
        // Calculate the lot size
        return Math.max(round((tradeAmount / strategy.takeProfitPips) * 0.01, 3), 0.001);
    }
    
    private void placeOrder(InstrumentStrategy strategy) throws JFException {
        String label = getName() + "_" + (++orderCounter) + "_" + strategy.toString();
        double lotSize = getLotSize(strategy);
        IOrder order = context.getEngine().submitOrder(label, strategy.instrument, strategy.orderCommand, lotSize, 0, 0);
        strategy.order = order;
    }

    private void handleMessage(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        if (State.CANCELED.equals(order.getState())) {
            onOrderCancelled(message);
        } else if (IMessage.Type.ORDER_FILL_OK.equals(message.getType())) {
            onOrderFilled(order);
        } else if (IMessage.Type.ORDER_CLOSE_OK.equals(message.getType())) {
            onOrderClosed(order);
        }
    }
    
    private void onOrderCancelled(IMessage message) throws JFException {
        IOrder order = message.getOrder();
        log(order.getLabel(), order.getCreationTime(), "Order has been cancelled: " + order.getInstrument() + " " + order.getOrderCommand() + " for " + order.getAmount() 
            + " lots. (" + message.getContent() + ")");
    }

    private void onOrderFilled(IOrder order) throws JFException {
        Instrument instrument = order.getInstrument();
        InstrumentStrategy strategy = getStrategy(order);
        
        // Set the take profit and stop loss prices
        double openPrice = order.getOpenPrice();
        double margin = instrument.getPipValue() * strategy.takeProfitPips;
        int negator = order.isLong() ? 1 : -1;

        order.setTakeProfitPrice(round(openPrice + (negator * margin), instrument.getPipScale()));
        order.setStopLossPrice(round(openPrice - (negator * margin), instrument.getPipScale()));

        log(order.getLabel(), order.getFillTime(), "Filled " + strategy + " " + order.getOrderCommand() + " for " 
                + order.getAmount() + " lots. " + strategy.getProfitStatus());
        endTime = order.getFillTime();
    }

    private void onOrderClosed(IOrder order) throws JFException {
        double profit = order.getProfitLossInUSD() - order.getCommissionInUSD();
        
        
        InstrumentStrategy strategy = getStrategy(order);
        strategy.updateProfit(order);
        
        if (profit < 0) {
            consecutiveLossCounter++;
            
            if (consecutiveLossCounter > maxConsecutiveLossCounter) {
                maxConsecutiveLossCounter = consecutiveLossCounter;
            }
        } else {
            consecutiveLossCounter = 0;
        }
        
        double totalProfit = this.getTotalProfit();
        this.maxProfit = Math.max(this.maxProfit, totalProfit);

        double currentDrawDown = this.maxProfit - totalProfit;
        this.maxDrawDown = Math.max(this.maxDrawDown, currentDrawDown);
        
        // Recalculate locked profit
        if (totalProfit > lockedProfit) {
            lockedProfit = totalProfit;
        }
        
        log(order.getLabel(), order.getCloseTime(), "Closed " + strategy + " " + order.getOrderCommand() + " of " + order.getAmount() + " lots for $" + round(profit, 2) + " " 
                + (profit >= 0 ? "PROFIT" : "LOSS") + ". " + strategy.getProfitStatus());
        
        int wins = this.getWins();
        int losses = this.getLosses();
        double winPct = 100.0 * wins / (wins + losses);
        
        log("Overall: totalProfit=$" + round(this.getTotalProfit(), 2) + ", winPct=" + round(winPct, 1) + "%, equity=$" + round(context.getAccount().getEquity(), 2) + "]");
        
        checkAndTrade(order.getCloseTime());
    }
    
    private void outputStats() {
        log("--------------------------------------------------------------------------------------------------");
        log("Strategy: " + getName() + " (" + DATE_FORMAT_MONTH.format(new Date(startTime)) + " to " + DATE_FORMAT_MONTH.format(new Date(endTime)) + ")");
        log("Parameters: TradeAmount=$" + round(baseTradeAmount, 2));
        
        double equity = context.getAccount().getEquity();
        double roi = (equity - startEquity) / startEquity * 100.0;
        roi = roi / ((endTime - startTime) / (1.0 * MILLIS_IN_YEAR));
        
        log("Total Equity: $" + round(equity, 2) + " (initial=$" + round(startEquity, 2) + ", profit=$" + round(equity - startEquity, 2) + ", roi=" + round(roi, 1) + "%pa)");

        int wins = this.getWins();
        int losses = this.getLosses();
        double winPct = 100.0 * wins / (wins + losses);
        log("Total Trades: " + (wins + losses)+ " (" + wins + " wins/" + losses + " losses, win%=" + round(winPct, 1) + "%)");
        
        log("Max Drawdown: $" + round(this.maxDrawDown, 2) + " (consecutiveLosses=" + maxConsecutiveLossCounter + ")");
        log(getName() + " strategy stopped.");
    }
    
    String getProfitStatus() {
        return "[profit=$" + round(this.getTotalProfit(), 2) + ", equity=$" + round(context.getAccount().getEquity(), 2) + "]";
    }
    
    // *****************************************************************************************************************
    // Public Methods - Implementation of the IStrategy interface
    // *****************************************************************************************************************
    public void onStart(IContext context) throws JFException {
        init(context);
    }

    public void onMessage(IMessage message) throws JFException {
        handleMessage(message);
    }

    public void onStop() throws JFException {
        outputStats();
    }

    public void onTick(Instrument instrument, ITick tick) throws JFException {
        for (InstrumentStrategy strategy : getStrategies(instrument)) {
            strategy.onTick(tick);
        }
    }

    public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
        if (Period.ONE_HOUR.equals(period) && Instrument.EURUSD.equals(instrument)) {
            checkAndTrade(askBar.getTime());
        }
        
        if (Period.WEEKLY.equals(period) && Instrument.EURUSD.equals(instrument)) {
            log("--------------------------------------------------------------------------------------------------");
            log("Weekly Stats", askBar.getTime(), "Profit: $" + round(this.getTotalProfit(), 2) + " (locked=$" + round(this.lockedProfit, 2) + ")");
            log("Best Strategies:");
            this.getBestStrategies().stream().forEach(s -> log(">>> " + s.toString(), askBar.getTime(), s.getProfitStatus()));
            log("--------------------------------------------------------------------------------------------------");
        }
    }

    public void onAccount(IAccount account) throws JFException {
    }
    
    // *****************************************************************************************************************
    // Static helper classes
    // *****************************************************************************************************************
    enum StrategyType {
        BUY, SELL;
    }
    
    static class InstrumentStrategy implements Comparable<InstrumentStrategy> {

        final int MIN_HISTORY_SIZE = 20;
        final int MAX_HISTORY_AGE = 21 * 24 * 3600 * 1000;
        
        Instrument instrument;
        StrategyType strategyType;
        int takeProfitPips;
        
        OrderCommand orderCommand;
        IOrder order;
        
        int wins;
        int losses;
        double profit;
        long totalTradeDuration;
        
        OrderCommand virtualOrderCommand;
        double virtualOrderPrice;
        long virtualOrderOpenTime;
        List<Long> virtualResults = new ArrayList<>();
        List<Long> virtualOrderDurations = new ArrayList<>();

        InstrumentStrategy(Instrument instrument, StrategyType strategyType, int takeProfitPips) {
            this.instrument = instrument;
            this.strategyType = strategyType;
            this.takeProfitPips = takeProfitPips;
            
            if (StrategyType.SELL.equals(strategyType)) {
                this.orderCommand = this.virtualOrderCommand = OrderCommand.SELL;
            } else {
                this.orderCommand = this.virtualOrderCommand = OrderCommand.BUY;
            }
        }
        
        void onTick(ITick tick) {
            double price = OrderCommand.BUY.equals(this.virtualOrderCommand) ? tick.getAsk() : tick.getBid();
            long time = tick.getTime();
            
            if (virtualOrderPrice == 0) {
                virtualOrderPrice = price;
                virtualOrderOpenTime = time;
            } else if (Math.abs(price - virtualOrderPrice) > (takeProfitPips * this.instrument.getPipValue())) {
                if (OrderCommand.BUY.equals(virtualOrderCommand) && price > virtualOrderPrice ||
                        OrderCommand.SELL.equals(virtualOrderCommand) && price < virtualOrderPrice) {
                    virtualResults.add(0, time);
                } else {
                    this.virtualResults.add(0, -time);
                }
                
                if (this.virtualResults.size() > MIN_HISTORY_SIZE) {
                    List<Long> extra = this.virtualResults.subList(MIN_HISTORY_SIZE, this.virtualResults.size());
                    this.virtualResults = this.virtualResults.subList(0, MIN_HISTORY_SIZE);
                    this.virtualResults.addAll(extra.stream().filter(r -> (time - Math.abs(r)) < MAX_HISTORY_AGE).collect(Collectors.toList()));
                }
                
                virtualOrderDurations.add(time - virtualOrderOpenTime);
                
                if (virtualOrderDurations.size() > this.virtualResults.size()) {
                    this.virtualOrderDurations = this.virtualOrderDurations.subList(0, this.virtualResults.size());
                }
                
                virtualOrderPrice = price;
                virtualOrderOpenTime = time;
            }
        }
        
        void switchOrderCommand() {
            this.virtualOrderCommand = OrderCommand.BUY.equals(this.virtualOrderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
        }
        
        void updateProfit(IOrder order) {
            double orderProfit = order.getProfitLossInUSD() - order.getCommissionInUSD();
            this.profit += orderProfit;
            
            if (orderProfit >= 0) {
                this.wins++;
            } else {
                this.losses++;
            }
            
            this.totalTradeDuration += 1.0 * (order.getCloseTime() - order.getFillTime());
        }
        
        String getProfitStatus() {
            return "[profit=$" + this.round(this.profit, 2) + ", win%=" + round(this.winPct(), 1) + "% (" + this.round(virtualWinPct(), 1) 
                + "% in " + this.virtualResults.size() + " results), avgTradeDuration=" + round(this.avgTradeDuration(), 1) + " days (" + round(this.avgVirtualTradeDuration(), 1) + " days)]";
        }
        
        int getTotalTrades() {
            return this.wins + this.losses;
        }
        
        double winPct() {
            if (this.getTotalTrades() == 0) {
                return 0;
            } else {
                return 100.0 * this.wins / this.getTotalTrades();
            }
        }
        
        double avgTradeDuration() {
            if (this.getTotalTrades() == 0) {
                return 0;
            } else {
                return 1.0 * this.totalTradeDuration / this.getTotalTrades() / MILLIS_IN_DAY;
            }
        }
        
        double virtualWinPct() {
            if (this.virtualResults.size() < MIN_HISTORY_SIZE) {
                return 0;
            }
            
            long wins = this.virtualResults.stream().filter(r -> r > 0).count();
            return 100.0 * wins / this.virtualResults.size(); 
        }
        
        double avgVirtualTradeDuration() {
            if (this.virtualOrderDurations.size() < MIN_HISTORY_SIZE) {
                return 0;
            } else {
                return this.virtualOrderDurations.stream()
                    .mapToDouble(d -> 1.0 * d / MILLIS_IN_DAY)
                    .average()
                    .getAsDouble();
            }
        }
        
        double round(double value, int precision) {
            return BigDecimal.valueOf(value).setScale(precision, RoundingMode.HALF_UP).doubleValue();
        }
        
        @Override
        public String toString() {
            return this.instrument.name() + "_" + this.strategyType + "_" + this.takeProfitPips;
        }

        @Override
        public int compareTo(InstrumentStrategy strategy) {
            return (int)(strategy.virtualWinPct() - this.virtualWinPct());
        }
    }
}
