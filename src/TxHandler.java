//import java.security.PublicKey;
import java.util.ArrayList;

//import com.sun.xml.internal.bind.v2.runtime.unmarshaller.XsiNilLoader.Array;

public class TxHandler {
	
	//initialize with empty UTXO pool
	private UTXOPool global_utxoPool = new UTXOPool();

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
    	//init with given UTXO pool
    	global_utxoPool = new UTXOPool(utxoPool);
    }

    
    private  enum TX_STATUS {
        GOOD,
        BAD_NO_UTXO_POOL,
        BAD_SIGN,
        BAD_DOUBLE_SPEND,
        BAD_NEG_OUT,
        BAD_SUM
    }
    
    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool, 
     * (2) the signatures on each input of {@code tx} are valid, 
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     *     values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx)
    {
    	TX_STATUS tx_status = TxHandler.evaluateTx(tx,global_utxoPool);
    	
    	if(tx_status == TX_STATUS.GOOD) return true;
    	else return false;
    }
    

    private static TX_STATUS evaluateTx(Transaction tx, UTXOPool utxoPool) {
       	double sum_in,sum_out;
    	sum_in= sum_out = 0;
    	//all UTXOs used in the input of this tx
     	ArrayList<UTXO> tx_UTXOs = new ArrayList<UTXO>();   
     	for(int i=0;i<tx.getInputs().size();i++)
//    	for (Transaction.Input txin :tx.getInputs())
    	{
    		Transaction.Input txin = tx.getInput(i);
    		//find pubkey corresponding to this input 
    		UTXO temp_UTXO = new UTXO(txin.prevTxHash,txin.outputIndex);
    		
            /* (1) all outputs claimed by {@code tx} are in the current UTXO pool, */
    		Transaction.Output utxo_tout = utxoPool.getTxOutput(temp_UTXO);
    		if (utxo_tout==null) return TX_STATUS.BAD_NO_UTXO_POOL;
    		
    		
        	/* (2) the signatures on each input of {@code tx} are valid, */
       		if(!Crypto.verifySignature(utxo_tout.address, tx.getRawDataToSign(i), txin.signature))
        			return TX_STATUS.BAD_SIGN;
       		
       		//add to in sum
       		sum_in += utxo_tout.value;
       		
            /* (3) no UTXO is claimed multiple times by {@code tx},*/
       		//add used UTXO to the temp pool in not existing else reject since double spending attempt
       		if(!tx_UTXOs.contains(temp_UTXO)) tx_UTXOs.add(temp_UTXO);
       		else return TX_STATUS.BAD_DOUBLE_SPEND;
    	}    	
    	
    	for (Transaction.Output txout :tx.getOutputs())
    	{
            /* (4) all of {@code tx}s output values are non-negative, and*/
    		if(txout.value < 0) return TX_STATUS.BAD_NEG_OUT;
    		
       		//add to out sum
    		sum_out += txout.value;
    	}
    	
        /* (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
             values; and false otherwise.*/
    	if(sum_out > sum_in) return TX_STATUS.BAD_SUM;
    	
    	return TX_STATUS.GOOD;

    }
    
    //look at all tx in chkTx and classify them as either good or bad tx
    private static void secondPass(UTXOPool given_pool,ArrayList<Transaction> chkTx,ArrayList<Transaction> goodTx,ArrayList<Transaction> badTx)
    {
    	//if any Tx from chkTx is using UTXO from good_pool then mark it as good Tx, else mark as bad Tx
    	for(Transaction tx : chkTx)
    	{
    		TX_STATUS tx_status = TxHandler.evaluateTx(tx,given_pool);
    		
    		if(tx_status==TX_STATUS.GOOD)  goodTx.add(tx);
    		//TODO this logic below can be re looked at , may be some of them are not bad
    		else badTx.add(tx);

    	}  
    	//at the end of this loop all tx from chkTx should be transferred over to either goodTx or badTx
    }

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
    	
    	ArrayList<Transaction> goodTx = new ArrayList<Transaction>();
    	ArrayList<Transaction> chkTx = new ArrayList<Transaction>();
    	ArrayList<Transaction> badTx = new ArrayList<Transaction>();
    	
    	for(Transaction tx : possibleTxs)
    	{
    		TX_STATUS tx_status = TxHandler.evaluateTx(tx,global_utxoPool);
    		if(tx_status==TX_STATUS.GOOD) goodTx.add(tx);
    		else if(tx_status==TX_STATUS.BAD_NO_UTXO_POOL) chkTx.add(tx);
    		else badTx.add(tx);
    	}   	
    	
    	//chk which ones in chkTx are valid
    	//Create new UTXO Pool from  new good Txs
    	UTXOPool new_utxo_pool = new UTXOPool();
    	for(Transaction gt : goodTx)
    	{
    		for(int i=0;i<gt.getOutputs().size();i++)
    		{
    			UTXO n_utxo = new UTXO(gt.getHash(),i);
    			new_utxo_pool.addUTXO(n_utxo, gt.getOutput(i));
    		}
    	}
    	
    	//also add to goodPool UTXO from chkTx as Tx from chkTx can take input from another chkTx as well
    	//TODO this logic can be re looked as it may get tricky
    	for(Transaction ct : chkTx)
    	{
    		for(int i=0;i<ct.getOutputs().size();i++)
    		{
    			UTXO n_utxo = new UTXO(ct.getHash(),i);
    			new_utxo_pool.addUTXO(n_utxo, ct.getOutput(i));
    		}
    	}   	
    	
    	//Case 1 & 2: Tx in chkTx whose input might be output from goodTx or even another chkTx
    	TxHandler.secondPass(new_utxo_pool,chkTx, goodTx,badTx);
 
    	//Finally, add & delete from global_utxoPool
    	//add all outputs from goodTx to global_utxoPool
    	for(Transaction final_gt : goodTx)
    	{
    		for(int i=0;i<final_gt.getOutputs().size();i++)
    		{
    			UTXO n_utxo = new UTXO(final_gt.getHash(),i);
    			global_utxoPool.addUTXO(n_utxo, final_gt.getOutput(i));
    		}
    	}
    	//delete all inputs to goodTx from global_utxoPool
    	for(Transaction final_gt : goodTx)
    	{
    		for(Transaction.Input ti : final_gt.getInputs())
    		{
    			UTXO n_utxo = new UTXO(ti.prevTxHash,ti.outputIndex);
    			global_utxoPool.removeUTXO(n_utxo);
    		}
    	}
    	
    	return goodTx.toArray(new Transaction[goodTx.size()]);
    }

}
