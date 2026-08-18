package com.you;

import javacard.framework.*;

public class Wallet extends Applet {

    // CLA
    private static final byte WALLET_CLA = (byte) 0xB0;

    // INS
    private static final byte INS_CREDIT  = (byte) 0x30;
    private static final byte INS_DEBIT   = (byte) 0x40;
    private static final byte INS_BALANCE = (byte) 0x50;

    // Maximum balance
    private static final short MAX_BALANCE = 10000;

    private short balance;

    private Wallet() {
        balance = 0;
        register();
    }

    public static void install(byte[] bArray, short bOffset, byte bLength) {
        new Wallet();
    }

    public void process(APDU apdu) {
        if (selectingApplet()) {
            return;
        }

        byte[] buffer = apdu.getBuffer();

        if (buffer[ISO7816.OFFSET_CLA] != WALLET_CLA) {
            ISOException.throwIt(ISO7816.SW_CLA_NOT_SUPPORTED);
        }

        switch (buffer[ISO7816.OFFSET_INS]) {

        case INS_CREDIT:
            credit(apdu);
            break;

        case INS_DEBIT:
            debit(apdu);
            break;

        case INS_BALANCE:
            getBalance(apdu);
            break;

        default:
            ISOException.throwIt(ISO7816.SW_INS_NOT_SUPPORTED);
        }
    }

    private void credit(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        apdu.setIncomingAndReceive();

        short amount =
            Util.getShort(buffer, ISO7816.OFFSET_CDATA);

        if (amount <= 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        if ((short)(balance + amount) > MAX_BALANCE) {
            ISOException.throwIt((short) 0x6A84);
        }

        balance += amount;

        ISOException.throwIt(ISO7816.SW_NO_ERROR);
    }

    private void debit(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        apdu.setIncomingAndReceive();

        short amount =
            Util.getShort(buffer, ISO7816.OFFSET_CDATA);

        if (amount <= 0) {
            ISOException.throwIt(ISO7816.SW_WRONG_DATA);
        }

        if (amount > balance) {
            ISOException.throwIt((short) 0x6A85);
        }

        balance -= amount;

        ISOException.throwIt(ISO7816.SW_NO_ERROR);
    }

    private void getBalance(APDU apdu) {
        byte[] buffer = apdu.getBuffer();

        Util.setShort(buffer, (short) 0, balance);

        apdu.setOutgoing();
        apdu.setOutgoingLength((short) 2);
        apdu.sendBytes((short) 0, (short) 2);
    }
}