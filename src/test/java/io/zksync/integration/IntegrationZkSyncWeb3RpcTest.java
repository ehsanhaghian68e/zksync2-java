package io.zksync.integration;

import io.zksync.abi.TransactionEncoder;
import io.zksync.crypto.signer.EthSigner;
import io.zksync.crypto.signer.PrivateKeyEthSigner;
import io.zksync.helper.ConstructorContract;
import io.zksync.helper.CounterContract;
import io.zksync.helper.Import;
import io.zksync.methods.response.*;
import io.zksync.protocol.ZkSync;
import io.zksync.protocol.core.Token;
import io.zksync.protocol.core.ZkBlockParameterName;
import io.zksync.protocol.provider.EthereumProvider;
import io.zksync.transaction.fee.DefaultTransactionFeeProvider;
import io.zksync.transaction.fee.ZkTransactionFeeProvider;
import io.zksync.transaction.manager.ZkSyncTransactionManager;
import io.zksync.transaction.response.ZkSyncTransactionReceiptProcessor;
import io.zksync.transaction.type.Transaction712;
import io.zksync.utils.ContractDeployer;
import io.zksync.utils.ZkSyncAddresses;
import io.zksync.wrappers.ERC20;
import io.zksync.wrappers.IL2Bridge;
import io.zksync.wrappers.NonceHolder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.protocol.exceptions.TransactionException;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.*;
import org.web3j.tx.gas.ContractGasProvider;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.gas.StaticGasProvider;
import org.web3j.utils.Convert;
import org.web3j.utils.Convert.Unit;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@Disabled
public class IntegrationZkSyncWeb3RpcTest {

    private static final String L1_NODE = "http://127.0.0.1:8545";
    private static final String L2_NODE = "http://127.0.0.1:3050";

    private static final Token ETH = Token.createETH();

    private static ZkSync zksync;
    private static Credentials credentials;
    private static EthSigner signer;

    private static ZkSyncTransactionReceiptProcessor processor;

    private static ZkTransactionFeeProvider feeProvider;

    private static String contractAddress;

    private static BigInteger chainId;

    @BeforeAll
    public static void setUp() {
        zksync = ZkSync.build(new HttpService(L2_NODE));
        credentials = Credentials.create(ECKeyPair.create(BigInteger.ONE));

        chainId = zksync.ethChainId().sendAsync().join().getChainId();

        signer = new PrivateKeyEthSigner(credentials, chainId.longValue());

        processor = new ZkSyncTransactionReceiptProcessor(zksync, 200, 100);

        feeProvider = new DefaultTransactionFeeProvider(zksync, ETH);

        contractAddress = "0xca9e8bfcd17df56ae90c2a5608e8824dfd021067";
    }

    @Test
    public void printChainId() {
        System.out.println(chainId);
        System.out.println(credentials.getAddress());
    }

    @Test
    public void sendTestMoney() {
        Web3j web3j = Web3j.build(new HttpService(L1_NODE));

        String account = web3j.ethAccounts().sendAsync().join().getAccounts().get(0);

        EthSendTransaction sent = web3j.ethSendTransaction(
                        Transaction.createEtherTransaction(account, null, Convert.toWei("1", Unit.GWEI).toBigInteger(), BigInteger.valueOf(21_000L),
                                credentials.getAddress(), Convert.toWei("1000000", Unit.ETHER).toBigInteger()))
                .sendAsync().join();

        assertResponse(sent);
    }

    @Test
    public void testGetBalanceOfTokenL1() throws IOException {
        Web3j web3j = Web3j.build(new HttpService(L1_NODE));
        EthGetBalance getBalance = web3j
                .ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST)
                .send();

        System.out.printf("%s: %d\n", credentials.getAddress(), Numeric.toBigInt(getBalance.getResult()));
    }

    @Test
    public void testDeposit() throws IOException {
        Web3j web3j = Web3j.build(new HttpService(L1_NODE));
        BigInteger chainId = web3j.ethChainId().send().getChainId();
        TransactionManager manager = new RawTransactionManager(web3j, credentials, chainId.longValue());
        ContractGasProvider gasProvider = new StaticGasProvider(Convert.toWei("1", Unit.GWEI).toBigInteger(), BigInteger.valueOf(555_000L));
        TransactionReceipt receipt = EthereumProvider
                .load(zksync, web3j, manager, gasProvider).join()
                .deposit(ETH, Convert.toWei("100", Unit.ETHER).toBigInteger(), credentials.getAddress()).join();

        System.out.println(receipt);
    }

    @Test
    public void testDepositToken() throws IOException {
        Token usdc = new Token("0xd35cceead182dcee0f148ebac9447da2c4d449c4", "0x72c4f199cb8784425542583d345e7c00d642e345", "USDC", 6);
        Web3j web3j = Web3j.build(new HttpService(L1_NODE));
        BigInteger chainId = web3j.ethChainId().send().getChainId();
        TransactionManager manager = new RawTransactionManager(web3j, credentials, chainId.longValue());
        ContractGasProvider gasProvider = new StaticGasProvider(Convert.toWei("1", Unit.GWEI).toBigInteger(), BigInteger.valueOf(555_000L));
        EthereumProvider provider = EthereumProvider.load(zksync, web3j, manager, gasProvider).join();
        TransactionReceipt approveReceipt = provider.approveDeposits(usdc, Optional.of(usdc.toBigInteger(10000000000L))).join();
        System.out.println(approveReceipt);

        TransactionReceipt receipt = provider.deposit(usdc, usdc.toBigInteger(10000000000L), credentials.getAddress()).join();

        System.out.println(receipt);
    }

    @Test
    public void testGetBalanceOfNative() throws IOException {
        EthGetBalance getBalance = zksync
                .ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST)
                .send();

        System.out.printf("%s: %d\n", credentials.getAddress(), Numeric.toBigInt(getBalance.getResult()));
    }

    @Test
    public void testGetNonce() throws IOException {
        EthGetTransactionCount getTransactionCount = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.LATEST)
                .send();

        System.out.printf("%s: %d\n", credentials.getAddress(), Numeric.toBigInt(getTransactionCount.getResult()));
    }

    @Test
    public void testGetDeploymentNonce() throws Exception {
        NonceHolder nonceHolder = NonceHolder.load(ZkSyncAddresses.NONCE_HOLDER_ADDRESS, zksync, new ReadonlyTransactionManager(zksync, credentials.getAddress()), new DefaultGasProvider());

        BigInteger nonce = nonceHolder.getDeploymentNonce(credentials.getAddress()).send();

        System.out.printf("%s: %d\n", credentials.getAddress(), nonce);
    }

    @Test
    public void testGetTransactionReceipt() throws IOException {
        TransactionReceipt receipt = zksync
                .ethGetTransactionReceipt("0xc47004cd0ab1d9d7866cfb6d699b73ea5872938f14541661b0f0132e5b8365d1").send()
                .getResult();

        System.out.println(receipt);
    }

    @Test
    public void testGetTransaction() throws IOException {
        org.web3j.protocol.core.methods.response.Transaction receipt = zksync
                .ethGetTransactionByHash("0xf6b0c2b7f815befda19e895efc26805585ae2002cd7d7f9e782d2c346a108ab6").send()
                .getResult();

        System.out.println(receipt.getNonce());
    }

    @Test
    public void testTransferNativeToSelf() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), ZkBlockParameterName.COMMITTED).send()
                .getTransactionCount();

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                "0x"
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();

        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                Convert.toWei(BigDecimal.valueOf(1), Unit.ETHER).toBigInteger(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testTransferNativeToSelfWeb3j_Legacy() throws Exception {
        Transfer transfer = new Transfer(zksync, new RawTransactionManager(zksync, credentials, chainId.longValue()));

        TransactionReceipt receipt = transfer.sendFunds(
                credentials.getAddress(),
                BigDecimal.valueOf(1),
                Unit.ETHER,
                Convert.toWei("3", Unit.GWEI).toBigInteger(),
                BigInteger.valueOf(50_000L)
        ).send();

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testTransferNativeToSelfWeb3j() throws Exception {
        Transfer transfer = new Transfer(zksync, new ZkSyncTransactionManager(zksync, signer, feeProvider));

        TransactionReceipt receipt = transfer.sendFunds(
                credentials.getAddress(),
                BigDecimal.valueOf(1),
                Unit.ETHER,
                BigInteger.ZERO,
                BigInteger.valueOf(50_000L)
        ).send();

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testTransferTokenToSelf() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), ZkBlockParameterName.COMMITTED).send()
                .getTransactionCount();

        String tokenAddress = zksync.zksGetConfirmedTokens(0, (short) 100).send()
                .getResult().stream()
                .filter(token -> !token.isETH())
                .map(Token::getL2Address)
                .findFirst().orElseThrow(IllegalArgumentException::new);
        Function transfer = ERC20.encodeTransfer(credentials.getAddress(), BigInteger.ZERO);
        String calldata = FunctionEncoder.encode(transfer);

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                tokenAddress,
                BigInteger.ZERO,
                BigInteger.ZERO,
                calldata
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();

        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testTransferTokenToSelfWeb3jContract() throws Exception {
        ERC20 erc20 = ERC20.load(ETH.getL2Address(), zksync,
                new ZkSyncTransactionManager(zksync, signer, feeProvider),
                feeProvider);

        TransactionReceipt receipt = erc20.transfer("0xe1fab3efd74a77c23b426c302d96372140ff7d0c", BigInteger.valueOf(1L)).send();

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testWithdraw() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), ZkBlockParameterName.COMMITTED).send()
                .getTransactionCount();
        String l2EthBridge = zksync.zksGetBridgeContracts().send().getResult().getL2EthDefaultBridge();
        final Function withdraw = new Function(
                IL2Bridge.FUNC_WITHDRAW,
                Arrays.asList(new Address(credentials.getAddress()),
                        new Address(ETH.getL2Address()),
                        new Uint256(ETH.toBigInteger(1))),
                Collections.emptyList());

        String calldata = FunctionEncoder.encode(withdraw);

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                l2EthBridge,
                BigInteger.ZERO,
                BigInteger.ZERO,
                calldata
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();

        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);
    }

    @Test
    public void testEstimateGas_Withdraw() throws IOException {
        String l2EthBridge = zksync.zksGetBridgeContracts().send().getResult().getL2EthDefaultBridge();
        final Function withdraw = new Function(
                IL2Bridge.FUNC_WITHDRAW,
                Arrays.asList(new Address(credentials.getAddress()),
                        new Address(ETH.getL2Address()),
                        new Uint256(ETH.toBigInteger(1))),
                Collections.emptyList());

        String calldata = FunctionEncoder.encode(withdraw);

        EthEstimateGas estimateGas = zksync.ethEstimateGas(io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                l2EthBridge,
                BigInteger.ZERO,
                BigInteger.ZERO,
                calldata
        )).send();

        assertResponse(estimateGas);
    }

    @Test
    public void testEstimateGas_TransferNative() throws IOException {
        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                "0x"
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();

        assertResponse(estimateGas);
    }

    @Test
    public void testEstimateFee_TransferNative() throws IOException {
        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                "0x"
        );

        ZksEstimateFee estimateGas = zksync.zksEstimateFee(estimate).send();

        assertResponse(estimateGas);
        System.out.println(estimateGas.getRawResponse());
    }

    @Test
    public void testEstimateGas_Execute() throws IOException {
        Function transfer = ERC20.encodeTransfer("0xe1fab3efd74a77c23b426c302d96372140ff7d0c", BigInteger.valueOf(1L));
        String calldata = FunctionEncoder.encode(transfer);

        EthEstimateGas estimateGas = zksync.ethEstimateGas(io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                "0x79f73588fa338e685e9bbd7181b410f60895d2a3",
                BigInteger.ZERO,
                BigInteger.ZERO,
                calldata
        )).send();

        assertResponse(estimateGas);
    }

    @Test
    public void testEstimateGas_DeployContract() throws IOException {
        EthEstimateGas estimateGas = zksync.ethEstimateGas(io.zksync.methods.request.Transaction.create2ContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                CounterContract.BINARY
        )).send();

        assertResponse(estimateGas);
    }

    @Test
    public void testEstimateFee_DeployContract() throws IOException {
        ZksEstimateFee estimateGas = zksync.zksEstimateFee(io.zksync.methods.request.Transaction.create2ContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                CounterContract.BINARY
        )).send();

        assertResponse(estimateGas);
    }

    @Test
    public void testDeployWeb3jContract() throws Exception {
        TransactionManager transactionManager = new ZkSyncTransactionManager(zksync, signer, feeProvider);
        CounterContract contract = CounterContract
                .deploy(zksync, transactionManager, feeProvider).send();

        assertNotNull(contract.getContractAddress());
        System.out.println(contract.getContractAddress());

        contractAddress = contract.getContractAddress();
    }

    @Test
    public void testReadWeb3jContract() throws Exception {
        TransactionManager transactionManager = new ZkSyncTransactionManager(zksync, signer, feeProvider);
        CounterContract zkCounterContract = CounterContract.load(contractAddress, zksync, transactionManager, feeProvider);

        BigInteger result = zkCounterContract.get().send();

        System.out.println(result);

        assertEquals(BigInteger.ZERO, result);
    }

    @Test
    public void testWriteWeb3jContract() throws Exception {
        TransactionManager transactionManager = new ZkSyncTransactionManager(zksync, signer, feeProvider);
        CounterContract zkCounterContract = CounterContract.load(contractAddress, zksync, transactionManager, feeProvider);

        TransactionReceipt receipt = zkCounterContract.increment(BigInteger.TEN).send();

        assertTrue(receipt::isStatusOK);

        BigInteger result = zkCounterContract.get().send();

        assertEquals(BigInteger.TEN, result);
    }

    @Test
    public void testDeployContract_Create() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send()
                .getTransactionCount(); // TODO: get nonce from holder contract

        String precomputedAddress = ContractDeployer.computeL2CreateAddress(new Address(credentials.getAddress()), nonce).getValue();

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                CounterContract.BINARY
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        contractAddress = receipt.getContractAddress();
        System.out.println("Deployed `CounterContract as: `" + contractAddress);
        assertEquals(contractAddress.toLowerCase(), precomputedAddress.toLowerCase());

        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(),
                contractAddress,
                FunctionEncoder.encode(CounterContract.encodeGet())
        );

        zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send();
    }

    @Test
    public void testDeployContractWithConstructor_Create() throws Exception {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send()
                .getTransactionCount();

        NonceHolder nonceHolder = NonceHolder.load(ZkSyncAddresses.NONCE_HOLDER_ADDRESS, zksync, new ReadonlyTransactionManager(zksync, credentials.getAddress()), new DefaultGasProvider());

        BigInteger deploymentNonce = nonceHolder.getDeploymentNonce(credentials.getAddress()).send();

        String precomputedAddress = ContractDeployer.computeL2CreateAddress(new Address(credentials.getAddress()), deploymentNonce).getValue();

        String constructor = ConstructorContract.encodeConstructor(BigInteger.valueOf(42), BigInteger.valueOf(43), false);

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                ConstructorContract.BINARY,
                constructor
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        contractAddress = receipt.getContractAddress();
        System.out.println("Deployed `ConstructorContract as: `" + contractAddress);
        assertEquals(contractAddress.toLowerCase(), precomputedAddress.toLowerCase());

        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(),
                contractAddress,
                FunctionEncoder.encode(ConstructorContract.encodeGet())
        );

        EthCall ethCall = zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send();
        assertResponse(ethCall);
    }

    @Test
    public void testDeployContract_Create2() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send()
                .getTransactionCount();

        byte[] salt = SecureRandom.getSeed(32);

        String precomputedAddress = ContractDeployer.computeL2Create2Address(new Address(credentials.getAddress()), Numeric.hexStringToByteArray(CounterContract.BINARY), new byte[]{}, salt).getValue();

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.create2ContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                CounterContract.BINARY,
                "0x",
                salt
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        contractAddress = receipt.getContractAddress();
        System.out.println("Deployed `CounterContract as: `" + contractAddress);
        assertEquals(contractAddress.toLowerCase(), precomputedAddress.toLowerCase());

        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(),
                contractAddress,
                FunctionEncoder.encode(CounterContract.encodeGet())
        );

        EthCall ethCall = zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send();
        assertResponse(ethCall);
    }

    @Test
    public void testDeployContractWithDeps_Create() throws Exception {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send()
                .getTransactionCount();

        NonceHolder nonceHolder = NonceHolder.load(ZkSyncAddresses.NONCE_HOLDER_ADDRESS, zksync, new ReadonlyTransactionManager(zksync, credentials.getAddress()), new DefaultGasProvider());

        BigInteger deploymentNonce = nonceHolder.getDeploymentNonce(credentials.getAddress()).send();

        String precomputedAddress = ContractDeployer.computeL2CreateAddress(new Address(credentials.getAddress()), deploymentNonce).getValue();

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                Import.BINARY,
                Collections.singletonList(Import.FOO_BINARY),
                "0x"
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        String contractAddress = ContractDeployer.extractContractAddress(receipt).getValue();
        System.out.println("Deployed `Import as: `" + contractAddress);
        assertEquals(contractAddress.toLowerCase(), precomputedAddress.toLowerCase());

        Function getFooName = Import.encodeGetFooName();

        Transaction call = Transaction.createEthCallTransaction(
                null,
                contractAddress,
                FunctionEncoder.encode(getFooName)
        );

        EthCall ethCall = zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send();
        assertResponse(ethCall);

        String fooName = (String) FunctionReturnDecoder.decode(ethCall.getValue(), getFooName.getOutputParameters()).get(0).getValue();
        assertEquals("Foo", fooName);
    }

    @Test
    public void testDeployContractWithDeps_Create2() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send()
                .getTransactionCount();

        byte[] salt = SecureRandom.getSeed(32);

        String precomputedAddress = ContractDeployer.computeL2Create2Address(new Address(credentials.getAddress()), Numeric.hexStringToByteArray(Import.BINARY), new byte[]{}, salt).getValue();

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.create2ContractTransaction(
                credentials.getAddress(),
                BigInteger.ZERO,
                BigInteger.ZERO,
                Import.BINARY,
                Collections.singletonList(Import.FOO_BINARY),
                "0x",
                salt
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        String contractAddress = ContractDeployer.extractContractAddress(receipt).getValue();
        System.out.println("Deployed `Import as: `" + contractAddress);
        assertEquals(contractAddress.toLowerCase(), precomputedAddress.toLowerCase());

        Function getFooName = Import.encodeGetFooName();

        Transaction call = Transaction.createEthCallTransaction(
                null,
                contractAddress,
                FunctionEncoder.encode(getFooName)
        );

        EthCall ethCall = zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send();
        assertResponse(ethCall);

        String fooName = (String) FunctionReturnDecoder.decode(ethCall.getValue(), getFooName.getOutputParameters()).get(0).getValue();
        assertEquals("Foo", fooName);
    }

    @Test
    public void testExecuteContract() throws IOException, TransactionException {
        BigInteger nonce = zksync
                .ethGetTransactionCount(credentials.getAddress(), ZkBlockParameterName.COMMITTED).send()
                .getTransactionCount();

        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(),
                contractAddress,
                FunctionEncoder.encode(CounterContract.encodeGet())
        );

        BigInteger value = Numeric.toBigInt(zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send().getValue());

        Function increment = CounterContract.encodeIncrement(BigInteger.ONE);
        String calldata = FunctionEncoder.encode(increment);

        io.zksync.methods.request.Transaction estimate = io.zksync.methods.request.Transaction.createFunctionCallTransaction(
                credentials.getAddress(),
                contractAddress,
                BigInteger.ZERO,
                BigInteger.ZERO,
                calldata
        );

        EthEstimateGas estimateGas = zksync.ethEstimateGas(estimate).send();
        EthGasPrice gasPrice = zksync.ethGasPrice().send();

        assertResponse(estimateGas);
        assertResponse(gasPrice);

        System.out.printf("Fee for transaction is: %d\n", estimateGas.getAmountUsed().multiply(gasPrice.getGasPrice()));

        Transaction712 transaction = new Transaction712(
                chainId.longValue(),
                nonce,
                estimateGas.getAmountUsed(),
                estimate.getTo(),
                estimate.getValueNumber(),
                estimate.getData(),
                BigInteger.valueOf(100000000L),
                gasPrice.getGasPrice(),
                credentials.getAddress(),
                estimate.getEip712Meta()
        );

        String signature = signer.getDomain().thenCompose(domain -> signer.signTypedData(domain, transaction)).join();
        byte[] message = TransactionEncoder.encode(transaction, TransactionEncoder.getSignatureData(signature));

        EthSendTransaction sent = zksync.ethSendRawTransaction(Numeric.toHexString(message)).send();

        assertResponse(sent);

        TransactionReceipt receipt = processor.waitForTransactionReceipt(sent.getResult());

        assertTrue(receipt::isStatusOK);

        BigInteger result = Numeric.toBigInt(zksync.ethCall(call, ZkBlockParameterName.COMMITTED).send().getValue());

        assertEquals(value.add(BigInteger.ONE), result);

    }

    @Test
    public void testGetAllAccountBalances() throws IOException {
        ZksAccountBalances response = zksync.zksGetAllAccountBalances(credentials.getAddress()).send();

        assertResponse(response);

        Map<String, BigInteger> balances = response.getBalances();

        System.out.println(balances);
    }

    @Test
    public void testGetConfirmedTokens() throws IOException {
        int offset = 0;
        short limit = 10; // Get first 10 confirmed tokens

        ZksTokens response = zksync.zksGetConfirmedTokens(offset, limit).send();

        assertResponse(response);
    }

    @Test
    public void testGetTokenPrice() throws IOException {
        ZksTokenPrice response = zksync.zksGetTokenPrice(ETH.getL2Address()).send();

        assertResponse(response);
    }

    @Test
    public void testGetL1ChainId() throws IOException {
        ZksL1ChainId response = zksync.zksL1ChainId().send();

        assertResponse(response);
    }

    @Test
    public void testGetBridgeAddresses() throws IOException {
        ZksBridgeAddresses response = zksync.zksGetBridgeContracts().send();

        assertResponse(response);
    }

    @Test
    public void testGetTestnetPaymaster() throws IOException {
        ZksTestnetPaymasterAddress response = zksync.zksGetTestnetPaymaster().send();

        assertResponse(response);
    }

    @Test
    public void testGetMainContract() throws IOException {
        ZksMainContract response = zksync.zksMainContract().send();

        assertResponse(response);
    }

    private void assertResponse(Response<?> response) {
        if (response.hasError()) {
            System.out.println(response.getError().getMessage());
            System.out.println(response.getError().getData());
        } else {
            System.out.println(response.getResult());
        }

        assertFalse(response::hasError);
    }

}
