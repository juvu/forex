package jforex.strategies;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.dukascopy.api.IAccount;
import com.dukascopy.api.IBar;
import com.dukascopy.api.IContext;
import com.dukascopy.api.ICurrency;
import com.dukascopy.api.IEngine.OrderCommand;
import com.dukascopy.api.IMessage;
import com.dukascopy.api.IOrder;
import com.dukascopy.api.IOrder.State;
import com.dukascopy.api.IStrategy;
import com.dukascopy.api.ITick;
import com.dukascopy.api.Instrument;
import com.dukascopy.api.JFException;
import com.dukascopy.api.Period;

public class HolyTrinityStrategy implements IStrategy {

	static final double BASE_LOT_SIZE = 0.01;
	static final double PROFIT_AMOUNT = 10;
	
	volatile AtomicInteger orderId;
	volatile IContext context;
	volatile Date currentDate;
	
	volatile Basket redBasket, blueBasket;
	
	
	// Track the ratio of each currency to the USD
	volatile Map<ICurrency, Double> ratios;

	@Override
	public void onStart(IContext context) throws JFException {
		this.context = context;
		currentDate = new Date();
		this.orderId = new AtomicInteger();
		
		redBasket = new Basket("RED");

		redBasket.addInstrumentInfo(Instrument.EURUSD, OrderCommand.BUY);
		redBasket.addInstrumentInfo(Instrument.EURGBP, OrderCommand.SELL);
		redBasket.addInstrumentInfo(Instrument.GBPUSD, OrderCommand.SELL);
		//redBasket.addInstrumentInfo(Instrument.EURJPY, OrderCommand.SELL);
		//redBasket.addInstrumentInfo(Instrument.GBPJPY, OrderCommand.BUY);
		//redBasket.addInstrumentInfo(Instrument.USDJPY, OrderCommand.SELL);
		//redBasket.addInstrumentInfo(Instrument.GBPAUD, OrderCommand.SELL);
		//redBasket.addInstrumentInfo(Instrument.EURAUD, OrderCommand.BUY);
		//redBasket.addInstrumentInfo(Instrument.AUDJPY, OrderCommand.BUY);
		//redBasket.addInstrumentInfo(Instrument.AUDUSD, OrderCommand.SELL);

		blueBasket = new Basket("BLUE");

		// The blue basket has opposite order commands to the red basket
		for (InstrumentInfo instrumentInfo : redBasket.instruments) {
			blueBasket.addInstrumentInfo(instrumentInfo.instrument, instrumentInfo.inverseOrderCommand());
		}
	}

	@Override
	public void onTick(Instrument instrument, ITick tick) throws JFException {
		redBasket.check();
		blueBasket.check();
	}

	@Override
	public void onBar(Instrument instrument, Period period, IBar askBar, IBar bidBar) throws JFException {
		if (ratios == null) {
			redBasket.open();
			blueBasket.open();
		}
	}

	@Override
	public void onMessage(IMessage message) throws JFException {
		switch (message.getType()) {
			case ORDER_FILL_OK:
				log("Filled" + message.getOrder().getLabel() + " @ $" + round(message.getOrder().getOpenPrice(), 2) + " (" + message.getOrder().getAmount() + ")");
				break;
			case ORDER_CLOSE_OK:
				log("Closed " + message.getOrder().getLabel() + " @ $" + round(message.getOrder().getOpenPrice(), 2) + " (" + message.getOrder().getAmount() + "). Profit = $" + round(message.getOrder().getProfitLossInAccountCurrency(), 2));
				break;
			case ORDER_FILL_REJECTED:
				log("Rejected " + message.getOrder().getLabel() + " @ $" + round(message.getOrder().getOpenPrice(), 2) + " (" + message.getOrder().getAmount() + ")");
				break;
			default:			
		}
	}

	@Override
	public void onAccount(IAccount account) throws JFException {
	}

	@Override
	public void onStop() throws JFException {
		log("LONG Basket Results:", true);
		log(redBasket.getProfitStats());
		log(redBasket.getOrderStats());
		log(redBasket.getWinPct());
		
		log("SHORT Basket Results:", true);
		log(blueBasket.getProfitStats());
		log(blueBasket.getOrderStats());
		log(blueBasket.getWinPct());
		
		log("");
	}
	
	// -------------------------------------------------------------------------------------------
	// Custom strategy methods
	// -------------------------------------------------------------------------------------------
	void updateRatios() throws JFException {
		ratios = new HashMap<>();
		
		for (InstrumentInfo info : redBasket.instruments) {
			if ("USD".equals(info.instrument.getPrimaryJFCurrency().getCurrencyCode())) {
				ratios.put(info.instrument.getSecondaryJFCurrency(), 1.0 / getCurrentPrice(info));
			} else if ("USD".equals(info.instrument.getSecondaryJFCurrency().getCurrencyCode())) {
				ratios.put(info.instrument.getPrimaryJFCurrency(), getCurrentPrice(info));
			}
		}
	}
	
	double getCurrentPrice(InstrumentInfo info) throws JFException {
		return context.getHistory().getTick(info.instrument, 0).getAsk();
	}
	
	void log(String message, boolean line) {
		DateFormat format = SimpleDateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);
		
		PrintStream out = context.getConsole().getOut();
		if (line) {
			out.println("----------------------------------------------------------------------------------------");
		}
		out.println(format.format(currentDate) + "-" + message);
	}
	
	void log(String message) {
		log(message, false);
	}
	
	double round(double value, int precision) {
		BigDecimal bd = new BigDecimal(value);
	    bd = bd.setScale(precision, RoundingMode.HALF_UP);
	    return bd.doubleValue(); 
	}
	
	// -------------------------------------------------------------------------------------------
	// Inner classes
	// -------------------------------------------------------------------------------------------
	class Basket {
		
		String name;
		List<InstrumentInfo> instruments = new ArrayList<>();
		List<IOrder> orders = new ArrayList<>();
		
		double profit;
		double pips;
		int wins;
		int losses;
		int rounds;

		Basket(String name) {
			this.name = name;
		}
		
		void addInstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
			this.instruments.add(new InstrumentInfo(instrument, orderCommand));
		}
		
		synchronized void open() throws JFException {
			updateRatios();
			
			for (InstrumentInfo info : instruments) {
				IOrder order = context.getEngine().submitOrder(getLabel(info), info.instrument, info.orderCommand, getLotSize(info));
				orders.add(order);
			}
		}
		
		String getLabel(InstrumentInfo info) {
			return name + "-" + info.instrument + "-" + info.orderCommand + "-" + orderId.getAndIncrement();
		}
		
		double getLotSize(InstrumentInfo info) {
			Double ratio = ratios.get(info.instrument.getPrimaryJFCurrency());
			double lotSize =  BASE_LOT_SIZE * ratio;
			
			if (lotSize < 0.001) {
				lotSize = 0.001;
			}
			
			return round(lotSize, 3);
		}
		
		synchronized void check() throws JFException {
			if (!orders.isEmpty()) {
				double profit = 0;
				
				for (IOrder order : orders) {
					profit += order.getProfitLossInAccountCurrency();
				}

				if (profit > PROFIT_AMOUNT) {
					close();
				}
				
			}
		}
		
		synchronized void close() throws JFException {
			for (IOrder order : orders) {
				if (State.FILLED.equals(order.getState()) || State.OPENED.equals(order.getState())) {
					order.close();
					order.waitForUpdate(State.CLOSED, State.CANCELED);
					profit += order.getProfitLossInAccountCurrency();
					pips += order.getProfitLossInPips();
				}
			}
			
			orders.clear();
			open();
		}
		
		String getProfitStats() {
			return "Total Profit: $" + round(redBasket.profit, 2) + " (" + redBasket.pips + "pips)";
		}
		
		String getOrderStats() {
			return "Total Orders: " + wins + losses + " (" + wins + " wins, " + losses + "losses)";
		}
		
		String getWinPct() {
			int total = wins + losses == 0 ? 1 : wins + losses;
			return "Win%: " + round(wins * 100.0 / total, 2) + "%";
		}
	}

	class InstrumentInfo {

		Instrument instrument;
		OrderCommand orderCommand;

		public InstrumentInfo(Instrument instrument, OrderCommand orderCommand) {
			this.instrument = instrument;
			this.orderCommand = orderCommand;
		}

		OrderCommand inverseOrderCommand() {
			return OrderCommand.BUY.equals(orderCommand) ? OrderCommand.SELL : OrderCommand.BUY;
		}
	}
}
