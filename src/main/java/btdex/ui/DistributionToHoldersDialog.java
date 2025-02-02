package btdex.ui;

import static btdex.locale.Translation.tr;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFormattedTextField;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import btdex.core.BurstNode;
import btdex.core.Constants;
import btdex.core.Globals;
import btdex.core.Market;
import btdex.core.NumberFormatting;
import btdex.ledger.BurstLedger;
import btdex.ledger.LedgerService;
import btdex.ledger.LedgerService.SignCallBack;
import signumj.crypto.SignumCrypto;
import signumj.entity.SignumAddress;
import signumj.entity.SignumID;
import signumj.entity.SignumValue;
import signumj.entity.response.Account;
import signumj.entity.response.Asset;
import signumj.entity.response.AssetBalance;
import signumj.entity.response.Transaction;
import signumj.entity.response.TransactionBroadcast;
import signumj.service.NodeService;

public class DistributionToHoldersDialog extends JDialog implements ActionListener, DocumentListener, SignCallBack {
	private static final long serialVersionUID = 1L;

	private static DecimalFormat PERC_FORMAT = new DecimalFormat("#.###");
	
	Market market;

	JTextField amountField, minHoldings, ignoredAccounts;
	ArrayList<SignumAddress> treasuryAccounts;
	
	JRadioButton methodDistButton, methodMultioutButton;
	
	boolean useMultiout = false;

	JTextPane conditions;
	JCheckBox acceptBox;

	JPasswordField pinField;
	private JTextField ledgerStatus;

	private JButton okButton;
	private JButton cancelButton;

	private SignumValue totalFees, suggestedFee;
	private SignumValue amountValue;
	
	List<AssetBalance> holders;

	private LinkedHashMap<SignumAddress, SignumValue> recipients;

	private int nPaid;

	private long minHoldingLong;

	public DistributionToHoldersDialog(JFrame owner, Market market) {
		super(owner, ModalityType.APPLICATION_MODAL);
		setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		
		setTitle(tr("token_distribution", market));
		this.market = market;

		JPanel fieldPanel = new JPanel(new GridLayout(0, 1, 4, 4));
		
		methodDistButton = new JRadioButton(tr("token_distribution_method_distribution"));
		methodMultioutButton = new JRadioButton(tr("token_distribution_method_multiout"));
		ButtonGroup group = new ButtonGroup();
	    group.add(methodDistButton);
	    group.add(methodMultioutButton);
	    methodDistButton.setSelected(true);
	    methodDistButton.addActionListener(this);
	    methodMultioutButton.addActionListener(this);
	    JPanel methodDistributionPanel = new JPanel();
	    methodDistributionPanel.add(methodDistButton);
	    methodDistributionPanel.add(methodMultioutButton);

		amountField = new JFormattedTextField(NumberFormatting.BURST.getFormat());
		amountField.setText("10");
		amountField.getDocument().addDocumentListener(this);
		ignoredAccounts = new JTextField(20);
		ignoredAccounts.setEnabled(false);
		ignoredAccounts.getDocument().addDocumentListener(this);
		
		NodeService NS = Globals.getInstance().getNS();
		Asset asset = NS.getAsset(market.getTokenID()).blockingGet();
		ignoredAccounts.setText(asset.getAssetAddress().getFullAddress());
		
		// type 2 and subtype 7 is the set treasury account tx
		Transaction[] txs = NS.getAccountTransactions(SignumAddress.fromId(asset.getAccountId()), 0, 100, false,
				2, 7).blockingGet();
		treasuryAccounts = new ArrayList<>();
		for(Transaction tx : txs) {
			SignumID id = SignumCrypto.getInstance().hashToId(tx.getReferencedTransactionFullHash());
			if(id.getSignedLongId() == asset.getAssetId().getSignedLongId()) {
				treasuryAccounts.add(tx.getRecipient());
			}
		}

		minHoldings = new JFormattedTextField(market.getNumberFormat().getFormat());
		minHoldings.setText("1");
		minHoldings.getDocument().addDocumentListener(this);
		
		fieldPanel.setBorder(BorderFactory.createTitledBorder(tr("token_distribution_config")));
		fieldPanel.add(methodDistributionPanel);
		fieldPanel.add(new Desc(tr("token_distribution_ignored"), ignoredAccounts));

		fieldPanel.add(new Desc(tr("send_amount", Constants.BURST_TICKER), amountField));
		fieldPanel.add(new Desc(tr("token_distribution_min_holdings", market.getTicker()), minHoldings));

		conditions = new JTextPane();
		conditions.setContentType("text/html");
		conditions.setEditable(false);
		conditions.setPreferredSize(new Dimension(600, 200));

		acceptBox = new JCheckBox(tr("dlg_accept_terms"));

		// Create a button
		JPanel buttonPane = new JPanel(new FlowLayout(FlowLayout.RIGHT));

		pinField = new JPasswordField(12);
		pinField.addActionListener(this);

		cancelButton = new JButton(tr("dlg_cancel"));
		okButton = new JButton(tr("dlg_ok"));
		getRootPane().setDefaultButton(okButton);

		cancelButton.addActionListener(this);
		okButton.addActionListener(this);

		if(Globals.getInstance().usingLedger()) {
			ledgerStatus = new JTextField(26);
			ledgerStatus.setEditable(false);
			buttonPane.add(new Desc(tr("ledger_status"), ledgerStatus));
			LedgerService.getInstance().setCallBack(this);
		}
		else
			buttonPane.add(new Desc(tr("dlg_pin"), pinField));
		buttonPane.add(new Desc(" ", okButton));
		buttonPane.add(new Desc(" ", cancelButton));

		// set action listener on the button

		JPanel content = (JPanel)getContentPane();
		content.setBorder(new EmptyBorder(4, 4, 4, 4));

		JPanel conditionsPanel = new JPanel(new BorderLayout());
		conditionsPanel.setBorder(BorderFactory.createTitledBorder(tr("dlg_terms_and_conditions")));
		JScrollPane scroll = new JScrollPane(conditions);
		scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS);
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setPreferredSize(conditions.getPreferredSize());
		conditionsPanel.add(scroll, BorderLayout.CENTER);

		conditionsPanel.add(acceptBox, BorderLayout.PAGE_END);
		
		suggestedFee = BurstNode.getInstance().getSuggestedFee().getCheapFee();

		JPanel centerPanel = new JPanel(new BorderLayout());
		centerPanel.add(fieldPanel, BorderLayout.PAGE_START);
		centerPanel.add(conditionsPanel, BorderLayout.CENTER);

		content.add(centerPanel, BorderLayout.CENTER);
		content.add(buttonPane, BorderLayout.PAGE_END);

		// Get all token holders
		holders = BurstNode.getInstance().getAssetBalanceAllAccounts(market);
		somethingChanged();
		pack();

		SwingUtilities.invokeLater(new Runnable() {  
			public void run() {
				amountField.requestFocusInWindow();
			}
		});
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if(e.getSource() == cancelButton) {
			setVisible(false);
		}
		
		if(e.getSource() == methodDistButton || e.getSource() == methodMultioutButton) {
			useMultiout = methodMultioutButton.isSelected();
			
			ignoredAccounts.setEnabled(useMultiout);
			
			somethingChanged();
			return;
		}

		if(e.getSource() == okButton || e.getSource() == pinField) {
			String error = null;
			Globals g = Globals.getInstance();
			
			if(error == null && (amountValue == null || amountValue.longValue() <= 0)) {
				error = tr("send_invalid_amount");
			}
			
			if(error == null && (recipients == null || recipients.size() == 0)) {
				error = tr("dlg_accept_first");
				acceptBox.requestFocus();
			}
			
			Account ac = BurstNode.getInstance().getAccount();
			if(error == null && (ac == null || ac.getBalance().compareTo(amountValue.add(totalFees)) < 0)) {
				error = tr("dlg_not_enough_balance");
			}

			if(error == null && !acceptBox.isSelected()) {
				error = tr("dlg_accept_first");
				acceptBox.requestFocus();
			}
			
			if(error == null && !g.usingLedger() && !g.checkPIN(pinField.getPassword())) {
				error = tr("dlg_invalid_pin");
				pinField.requestFocus();
			}

			if(error!=null) {
				Toast.makeText((JFrame) this.getOwner(), error, Toast.Style.ERROR).display(okButton);
				return;
			}
			
			if(g.usingLedger()) {
				if(BurstLedger.isAppAvailable())
					Toast.makeText((JFrame) this.getOwner(), tr("ledger_auth"), Toast.Style.NORMAL).display(okButton);
				else {
					Toast.makeText((JFrame) this.getOwner(), tr("ledger_error"), Toast.Style.ERROR).display(okButton);
					return;
				}
			}

			// all set, lets build the transaction
			try {
				setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				pinField.setEnabled(false);
				okButton.setEnabled(false);
				
				if(useMultiout) {
					nPaid = 0;

					LinkedHashMap<SignumAddress, SignumValue> batch = new LinkedHashMap<>();
					for(SignumAddress a : recipients.keySet()) {
						batch.put(a, recipients.get(a));
						nPaid++;
						if(batch.size()==64) {
							byte[] utx = g.getNS().generateMultiOutTransaction(g.getPubKey(), suggestedFee.multiply(BigInteger.valueOf(6)), Constants.BURST_EXCHANGE_DEADLINE, batch).blockingGet();
							byte[] signedTransactionBytes = g.signTransaction(pinField.getPassword(), utx);
							reportSigned(signedTransactionBytes, null);
							
							batch.clear();
						}
					}
					
					if(batch.size() > 0) {
						byte[] utx;
						if(batch.size() == 1) {
							SignumAddress receiver = batch.keySet().iterator().next();
							utx = g.getNS().generateTransaction(receiver, g.getPubKey(), batch.get(receiver), suggestedFee, Constants.BURST_EXCHANGE_DEADLINE, null).blockingGet();
						}
						else
							utx = g.getNS().generateMultiOutTransaction(g.getPubKey(), suggestedFee.multiply(BigInteger.valueOf(6)),
									Constants.BURST_EXCHANGE_DEADLINE, batch).blockingGet();
						
						byte[] signedTransactionBytes = g.signTransaction(pinField.getPassword(), utx);
						reportSigned(signedTransactionBytes, null);
					}
				}
				else {
					byte[] utx = g.getNS().generateDistributeToAssetHolders(g.getPubKey(), market.getTokenID(),
							SignumValue.fromNQT(minHoldingLong), amountValue, SignumID.fromLong(0), SignumValue.fromNQT(0),
							totalFees, Constants.BURST_EXCHANGE_DEADLINE).blockingGet();
		
					byte[] signedTransactionBytes = g.signTransaction(pinField.getPassword(), utx);
					reportSigned(signedTransactionBytes, null);
				}
				
				setVisible(false);
			}
			catch (Exception ex) {
				Toast.makeText((JFrame) this.getOwner(), ex.getCause().getMessage(), Toast.Style.ERROR).display(okButton);
			}
			setCursor(Cursor.getDefaultCursor());
			pinField.setEnabled(true);
			okButton.setEnabled(true);
		}
	}

	private void somethingChanged(){
		if(acceptBox == null)
			return;

		acceptBox.setSelected(false);

		amountValue = null;

		if(amountField.getText().length()==0 || minHoldings.getText().length()==0)
			return;
		
		String ignored = ignoredAccounts.getText().toUpperCase();
		String holdersText = "<table>";

		holdersText += "<tr><th>" + tr("main_accounts") + "</th>";
		holdersText += "<th>" + tr("token_distribution_holdings", market.getTicker()) + "</th>";
		holdersText += "<th>%</th>";
		holdersText += "<th>" + Constants.BURST_TICKER + "</th>";
		holdersText += "</tr>";
		
        recipients = new LinkedHashMap<SignumAddress, SignumValue>();
		
		try {
			// For token, price is in BURST, others price is on the selected market
			Number amountN = NumberFormatting.parse(amountField.getText());
			Number minHoldingN = NumberFormatting.parse(minHoldings.getText());
			minHoldingLong = (long)(minHoldingN.doubleValue()*market.getFactor());
			long circulatingTokens = 0;
			
			for(AssetBalance h : holders) {
				if(useMultiout && (h.getBalance().longValue() < minHoldingLong || ignored.contains(h.getAccountAddress().getFullAddress()))) {
					continue;
				}
				if(!useMultiout && (h.getBalance().longValue() < minHoldingLong || treasuryAccounts.contains(h.getAccountAddress()))){
					continue;
				}

				if(useMultiout) {
					circulatingTokens += h.getBalance().longValue();
				}
				else {
					circulatingTokens += h.getBalance().longValue();
				}
			}
			
			for(AssetBalance h : holders) {
				String address = h.getAccountAddress().getFullAddress();
				if(useMultiout && (h.getBalance().longValue() < minHoldingLong || ignored.contains(address))) {
					continue;
				}
				if(!useMultiout && (h.getBalance().longValue() < minHoldingLong || treasuryAccounts.contains(h.getAccountAddress()))){
					continue;
				}
					
				holdersText += "<tr>";
				holdersText += "<td>" + address + "</td><td>" + market.format(h.getBalance().longValue()) + "</td><td>";
				
				long tokenBalance = useMultiout ? h.getBalance().longValue() : h.getBalance().longValue();
					
				SignumValue value = SignumValue.fromSigna(amountN.doubleValue()*tokenBalance/circulatingTokens);
				holdersText += " " + PERC_FORMAT.format(h.getBalance().longValue() / (double)circulatingTokens * 100D)
				+ "</td><td>" + NumberFormatting.BURST.format(value.longValue());

				recipients.put(h.getAccountAddress(), value);
				holdersText += "</td></tr>";
			}
			holdersText += "</table>";

			amountValue = SignumValue.fromSigna(amountN.doubleValue());
		} catch (ParseException e) {
			e.printStackTrace();
		}

		int nTransactions = 1;
		totalFees = SignumValue.fromNQT(Math.max(Constants.FEE_QUANT, (Constants.FEE_QUANT * recipients.size())/10));
		if(useMultiout) {
			nTransactions = recipients.size()/64 + (recipients.size() % 64 == 0 ? 0 : 1);
			totalFees = suggestedFee.multiply(BigDecimal.valueOf(nTransactions*6));
		}
		
		StringBuilder terms = new StringBuilder();
		terms.append(PlaceOrderDialog.HTML_STYLE);
		
		terms.append("<h3>").append(tr("token_distribution_brief", amountField.getText(), Constants.BURST_TICKER, recipients.size(), market.getTicker())).append("</h3>");
		terms.append("<p>").append(tr("token_distribution_details",
				nTransactions,
				NumberFormatting.BURST.format(totalFees.longValue()), Constants.BURST_TICKER)).append("</p>");
		
		terms.append(holdersText);

		if(!conditions.getText().equals(terms.toString())) {
			conditions.setText(terms.toString());
			conditions.setCaretPosition(0);
		}
	}

	@Override
	public void insertUpdate(DocumentEvent e) {
		somethingChanged();
	}

	@Override
	public void removeUpdate(DocumentEvent e) {
		somethingChanged();
	}

	@Override
	public void changedUpdate(DocumentEvent e) {
		somethingChanged();
	}

	@Override
	public void ledgerStatus(String txt) {
		ledgerStatus.setText(txt);
		ledgerStatus.setCaretPosition(0);
	}

	@Override
	public void reportSigned(byte[] signed, byte[] signed2) {
		if(!isVisible())
			return; // already closed by cancel, so we will not broadcast anyway
		
		if(signed == null) {
			// when coming from the hardware wallet
			okButton.setEnabled(true);
			amountField.setEnabled(true);

			setCursor(Cursor.getDefaultCursor());
			
			Toast.makeText((JFrame) this.getOwner(), tr("ledger_denied"), Toast.Style.ERROR).display(okButton);
			
			return;
		}
		TransactionBroadcast tb = Globals.getInstance().getNS().broadcastTransaction(signed).blockingGet();
		if(nPaid == recipients.size())
			setVisible(false);

		Toast.makeText((JFrame) this.getOwner(),
				tr("send_tx_broadcast", tb.getTransactionId().toString()), Toast.Style.SUCCESS).display();
	}
}
