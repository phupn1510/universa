/*
 * Copyright (c) 2017 Sergey Chernov, iCodici S.n.C, All Rights Reserved
 *
 * Written by Sergey Chernov <real.sergeych@gmail.com>
 *
 */

package com.icodici.universa.contract;


import com.icodici.crypto.PrivateKey;
import com.icodici.crypto.PublicKey;
import com.icodici.universa.HashId;
import com.icodici.universa.HashIdentifiable;
import com.icodici.universa.node2.Quantiser;
import net.sergeych.biserializer.*;
import net.sergeych.boss.Boss;
import net.sergeych.tools.Binder;
import net.sergeych.utils.Bytes;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The main contract and its counterparts needed for registration submission bundled together. The main contract is
 * specified in constructor or with {@link #setContract(Contract)}, and obtained by {@link #getContract()}. The
 * counterparts are: possible siblings (for example, of split operation), and revoking contracts (when creating new
 * revision, joining contracts and so on). All of these should be presented the the Network in the form of sealed
 * binaries, that is when {@link TransactionPack} is used.
 * <p>
 * Packed format of transaction pack could be very well used instead of {@link Contract#seal()} binaries as it holds
 * together the window around the contract graph (parent and siblings) needed to approve its state. It is advised to use
 * {@link Contract#getPackedTransaction()} to save the Contract and {@link Contract#fromPackedTransaction(byte[])} to
 * restore, respectively, as it holds together all relevant data. The file extension for it should be .unicon, as the
 * latter function is able to read and reconstruct lefacy v2 sealed contracts, current v3 seald contracts and packed
 * TransactionPack instances, it is a universal format and a universal routine to load it. See {@link
 * Contract#fromPackedTransaction(byte[])} for more.
 * <p>
 * Indeed, it is possible to keep all contract binaries separately and put them together for submission only, thus
 * saving the space.
 * <p>
 * The legacy v2 self-contained format is no more in use as the size of the hashed binary constantly grows.
 * <p>
 * A word of terminology.
 * <p>
 * We name as the <i>transaction contract</i> any contract that is going to be passed to the network for verification
 * and approval; it could and should contain other contracts as revoking as well as new items (siblings) to create.
 * These <i>referenced</i> contracts do not need to be addded separately.
 * <p>
 * Note. To put several operations in an atomic transaction, put iy all into a single top-level contract.
 * <p>
 * This implementation is not thread safe. Synchronize your access if need.
 */
@BiType(name = "TransactionPack")
public class TransactionPack implements BiSerializable {

    private byte[] packedBinary;
    private boolean reconstructed = false;
    private Map<HashId, Contract> references = new HashMap<>();
    private Set<PublicKey> keysForPack = new HashSet<>();
    private Contract contract;

    /**
     * Create a transaction pack and add a contract to it. See {@link TransactionPack#TransactionPack()} and {@link
     * #setContract(Contract)} for more information.
     *
     * @param contract is {@link Contract} to be send with this {@link TransactionPack}
     */
    public TransactionPack(Contract contract) {
        this();
        setContract(contract);
        for (PrivateKey key : contract.getKeysToSignWith())
            addKeys(key.getPublicKey());
    }

    /**
     * The main contract of this {@link TransactionPack}.
     *
     * @return a {@link Contract}
     */
    public Contract getContract() {
        return contract;
    }

    public Contract getReference(HashId id) {
        return references.get(id);
    }

    public Contract getReference(HashIdentifiable hid) {
        return getReference(hid.getId());
    }

    public Set<PublicKey> getKeysForPack() {
        return keysForPack;
    }

    public TransactionPack() {
    }

    /**
     * Add contract that already includes all its references. It will be added as a contract per transactions, while its
     * references will be added to references if not already included.
     * <p>
     * This is extremely important that the contract is properly sealed as well as its possibly new items, and revoking
     * items have binary image attached. <b>Do not ever seal the approved contract</b>: it will break it's id and cancel
     * the approval blockchain, so the new state will not be approved. If it was done by mistake, reload the packed
     * contract to continue.
     *
     * @param c contract to append to the list of transactions.
     */
    public void setContract(Contract c) {
        if (contract != null)
            throw new IllegalArgumentException("the contract is already added");
        contract = c;
        packedBinary = null;

        putAllSubitemsToReferencesRecursively(c);
        c.setTransactionPack(this);
    }


    protected synchronized void putAllSubitemsToReferencesRecursively(Contract c) {
        for (Contract r : c.getRevoking()) {
            putReference(r);
            putAllSubitemsToReferencesRecursively(r);
        }
        for (Contract n : c.getNew()) {
            putReference(n);
            putAllSubitemsToReferencesRecursively(n);
        }

    }

    /**
     * Direct add the reference. Not recommended as {@link #setContract(Contract)} already does it for all referenced
     * contracts. Use it to add references not mentioned in the added contracts.
     *
     * @param reference is {@link Contract} for adding
     */
    public void addReference(Contract reference) {
        if (!references.containsKey(reference.getId())) {
            packedBinary = null;
            references.put(reference.getId(), reference);
        }
    }

    /**
     * Add public key to {@link TransactionPack} that will match with anonimous ids in the {@link Contract} roles.
     *
     * @param keys is {@link PublicKey} that will be compare with anonimous ids.
     */
    public void addKeys(PublicKey... keys) {
        for (PublicKey key : keys) {
            if (!keysForPack.contains(key)) {
                keysForPack.add(key);
            }
        }
    }

    /**
     * store the referenced contract. Called by the {@link #setContract(Contract)} and only useful if the latter is
     * overriden. Referenced contracts are not processed by themselves but only as parts of the contracts add with
     * {@link #setContract(Contract)}
     *
     * @param contract is {@link Contract} for putting
     */
    protected synchronized void putReference(Contract contract) {
//        if (!contract.isOk())
//            throw new IllegalArgumentException("referenced contract has errors");
        references.put(contract.getId(), contract);
    }

    @Override
    public void deserialize(Binder data, BiDeserializer deserializer) throws IOException {

        synchronized (this) {
            // It is local quantiser that should throw exception
            // if limit is got while deserializing TransactionPack.
            Quantiser quantiser = new Quantiser();
            quantiser.reset(Contract.getTestQuantaLimit());

            List<Bytes> referenceBytesList = deserializer.deserializeCollection(
                    data.getListOrThrow("references")
            );

            HashMap<ContractDependencies, Bytes> allContractsTrees = new HashMap<>();
            List<HashId> allContractsHids = new ArrayList<>();
            ArrayList<Bytes> sortedReferenceBytesList = new ArrayList<>();

            if (referenceBytesList != null) {
                // First of all extract contracts dependencies from references
                for (Bytes b : referenceBytesList) {
                    ContractDependencies ct = new ContractDependencies(b.toArray());
                    allContractsTrees.put(ct, b);
                    allContractsHids.add(ct.id);
                }

                // then recursively from ends of dependencies tree to top go throw it level by level
                // and add items to references on the each level of tree's hierarchy
                do {
                    // first add contract from ends of trees, means without own subitems
                    sortedReferenceBytesList = new ArrayList<>();
                    List<ContractDependencies> removingContractDependencies = new ArrayList<>();
                    for (ContractDependencies ct : allContractsTrees.keySet()) {
                        if (ct.dependencies.size() == 0) {
                            sortedReferenceBytesList.add(allContractsTrees.get(ct));
                            removingContractDependencies.add(ct);
                        }
                    }

                    // remove found items from tree's list
                    for (ContractDependencies ct : removingContractDependencies) {
                        allContractsTrees.remove(ct);
                    }

                    // then add contract with already exist subitems in the references or will never find in the tree
                    removingContractDependencies = new ArrayList<>();
                    for (ContractDependencies ct : allContractsTrees.keySet()) {
                        boolean allDependenciesSafe = true;
                        for (HashId hid : ct.dependencies) {
                            if (!references.containsKey(hid) && allContractsHids.contains(hid)) {
                                allDependenciesSafe = false;
                            }
                        }
                        if (allDependenciesSafe) {
                            sortedReferenceBytesList.add(allContractsTrees.get(ct));
                            removingContractDependencies.add(ct);
                        }
                    }

                    // remove found items from tree's list
                    for (ContractDependencies ct : removingContractDependencies) {
                        allContractsTrees.remove(ct);
                    }

                    // add found binaries on the hierarchy level to references
                    for (int i = 0; i < sortedReferenceBytesList.size(); i++) {
                        Contract c = new Contract(sortedReferenceBytesList.get(i).toArray(), this);
                        quantiser.addWorkCostFrom(c.getQuantiser());
                        references.put(c.getId(), c);
                    }

                    // then repeat until we can find hierarchy
                } while (sortedReferenceBytesList.size() != 0);

                // finally add not found binaries on the hierarchy levels to references
                for (Bytes b : allContractsTrees.values()) {
                    Contract c = new Contract(b.toArray(), this);
                    quantiser.addWorkCostFrom(c.getQuantiser());
                    references.put(c.getId(), c);
                }
            }

            List<Object> keysList = deserializer.deserializeCollection(data.getList("keys", new ArrayList<>()));

            keysForPack = new HashSet<>();
            if(keysList != null) {
                for (Object x : keysList) {
                    if (x instanceof Bytes)
                        x = ((Bytes) x).toArray();
                    if (x instanceof byte[]) {
                        keysForPack.add(new PublicKey((byte[]) x));
                    } else {
                        throw new IllegalArgumentException("unsupported key object: " + x.getClass().getName());
                    }
                }
            }

            byte[] bb = data.getBinaryOrThrow("contract");
            contract = new Contract(bb, this);
            quantiser.addWorkCostFrom(contract.getQuantiser());
        }
    }

    @Override
    public Binder serialize(BiSerializer serializer) {
        synchronized (this) {

            Binder of = Binder.of(
                    "contract", contract.getLastSealedBinary(),
                    "references",
                    serializer.serialize(
                            references.values().stream()
                                    .map(x -> x.getLastSealedBinary()).collect(Collectors.toList())
                    )

            );
            if(keysForPack.size() > 0) {
                of.set("keys", serializer.serialize(
                        keysForPack.stream()
                                .map(x -> x.pack()).collect(Collectors.toList())
                ));
            }
            return of;
        }
    }

    public final boolean isReconstructed() {
        return reconstructed;
    }

    /**
     * Unpack either old contract binary (all included), or newer transaction pack. Could be used to load old contracts
     * to perform a transaction.
     *
     * @param packOrContractBytes binary that was packed by {@link TransactionPack#pack()}
     * @param allowNonTransactions if false, non-trasnaction pack data will cause IOException.
     *
     * @return transaction, either unpacked or reconstructed from the self-contained v2 contract
     * @throws IOException if something went wrong
     */
    public static TransactionPack unpack(byte[] packOrContractBytes, boolean allowNonTransactions) throws IOException {

        Object x = Boss.load(packOrContractBytes);

        if (x instanceof TransactionPack) {
            return (TransactionPack) x;
        }

        if (!allowNonTransactions)
            throw new IOException("expected transaction pack");

        // This is an old v2 self-contained contract or a root v3 contract, no revokes, no siblings.
        TransactionPack tp = new TransactionPack();
        tp.reconstructed = true;
        tp.packedBinary = packOrContractBytes;
        tp.contract = new Contract(packOrContractBytes, tp);
        return tp;
    }

    /**
     * Unpack either old contract binary (all included), or newer transaction pack. Could be used to load old contracts
     * to perform a transaction.
     *
     * @param packOrContractBytes binary that was packed by {@link TransactionPack#pack()}
     *
     * @return transaction, either unpacked or reconstructed from the self-contained v2 contract
     * @throws IOException if something went wrong
     */
    public static TransactionPack unpack(byte[] packOrContractBytes) throws IOException {
        return unpack(packOrContractBytes, true);
    }


    /**
     * Shortcut to {@link Boss#pack(Object)} for this.
     *
     * @return packed binary
     */
    public synchronized byte[] pack() {
        if (packedBinary == null)
            packedBinary = Boss.pack(this);
        return packedBinary;
    }

    /**
     * @return map of referenced contracts
     */
    public Map<HashId, Contract> getReferences() {
        return references;
    }

    static {
        DefaultBiMapper.registerClass(TransactionPack.class);
    }

    /**
     * Trace the tree of contracts references on the stdout.
     */
    public void trace() {
        System.out.println("Transaction pack");
        System.out.println("\tContract:");
        System.out.println("\t\t" + contract.getId());
        contract.getNewItems().forEach(x -> System.out.println("\t\t\tnew: " + x.getId()));
        contract.getRevokingItems().forEach(x -> System.out.println("\t\t\trevoke: " + x.getId()));
        System.out.println("\tReferences:");
        references.forEach((hashId, contract) -> System.out.println("\t\t" + hashId + " -> " + contract.getId()));
    }

    /**
     * Class that extracts subitems from given contract bytes and build dependencies. But do not do anything more.
     */
    public class ContractDependencies {
        private final Set<HashId> dependencies = new HashSet<>();
        private final HashId id;

        public ContractDependencies(byte[] sealed) throws IOException {
            this.id = HashId.of(sealed);
            Binder data = Boss.unpack(sealed);
            byte[] contractBytes = data.getBinaryOrThrow("data");

            // This must be explained. By default, Boss.load will apply contract transformation in place
            // as it is registered BiSerializable type, and we want to avoid it. Therefore, we decode boss
            // data without BiSerializer and then do it by hand calling deserialize:
            Binder payload = Boss.load(contractBytes, null);

            int apiLevel = data.getIntOrThrow("version");

            if (apiLevel < 3) {
                // no need to build tree - subitems will be reconstructed from binary, not from references
            } else {
                // new format: only references are included
                for (Binder b : (List<Binder>) payload.getList("revoking", Collections.EMPTY_LIST)) {
                    HashId hid = HashId.withDigest(b.getBinaryOrThrow("composite3"));
                    dependencies.add(hid);
                }
                for (Binder b : (List<Binder>) payload.getList("new", Collections.EMPTY_LIST)) {
                    HashId hid = HashId.withDigest(b.getBinaryOrThrow("composite3"));
                    dependencies.add(hid);
                }
            }
        }
    }
}
