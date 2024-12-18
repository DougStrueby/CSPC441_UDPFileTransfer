/**
 *  StopWaitFtp class
 *
 * CPSC 441
 * Assignment 3
 *
 * @author Douglas Strueby
 * @version 2024
 *
 */

import java.io.*;
import java.net.*;
import java.util.Timer;
import java.util.TimerTask;

public class StopWaitFtp {

    private DatagramSocket udpSocket;
    private Socket tcpSocket;
    private int connTimeout;
    private int rtxTimeout;
    private Timer retransmissionTimer;

    /**
     * Constructor to initialize the timeouts
     *
     * @param connTimeout   Connection timeout in milli-seconds
     * @param rtxTimeout    Retransmission  timeout in milli-seconds
     */
    public StopWaitFtp(int connTimeout, int rtxTimeout) {
        this.connTimeout = connTimeout;
        this.rtxTimeout = rtxTimeout;
        this.retransmissionTimer = new Timer();
    }

    /**
     * Sends a file to the specified server using Stop-and-Wait
     *
     * @param serverName    Name of the server
     * @param serverPort    Server port at which the web server listens > 1024
     * @param fileName      Name of the file being sent
     * @return              True if the file transfer is successful, false otherwise
     */
    public boolean send(String serverName, int serverPort, String fileName) {
        try {
            //Initializing TCP socket
            this.tcpSocket = new Socket(serverName, serverPort);
            DataOutputStream tcpOut = new DataOutputStream(tcpSocket.getOutputStream());
            DataInputStream tcpIn = new DataInputStream(tcpSocket.getInputStream());

            //Initializing UDP socket
            this.udpSocket = new DatagramSocket();

            //TCP handshake
            tcpOut.writeUTF(fileName); //sending file as UTF
            File file = new File(fileName);

            tcpOut.writeLong(file.length()); //sending file length as long
            tcpOut.writeInt(udpSocket.getLocalPort()); //sending local port as int
            tcpOut.flush();

            int udpServerPort = tcpIn.readInt(); //Receiving UDP port number
            int initSeq = tcpIn.readInt(); //Receiving initial sequence number

            tcpSocket.close();
            udpSocket.setSoTimeout(connTimeout);

            InetAddress serverAddress = InetAddress.getByName(serverName);

            try (FileInputStream fileInput = new FileInputStream(file)) {
                //Setup for file transmission
                byte[] buffer = new byte[FtpSegment.MAX_PAYLOAD_SIZE];
                int bytesRead;
                int currentSeq = initSeq;
                boolean ackReceived;

                //File transmission loop
                while ((bytesRead = fileInput.read(buffer)) != -1) {
                    //Creating segment for current chunk
                    FtpSegment segment = new FtpSegment(currentSeq, buffer, bytesRead);
                    DatagramPacket packet = FtpSegment.makePacket(segment, serverAddress, udpServerPort);

                    ackReceived = false;

                    //Initial send of the packet
                    udpSocket.send(packet);
                    System.out.println("send " + currentSeq);

                    //Schedule retransmissions
                    int finalCurrentSeq = currentSeq;
                    retransmissionTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            try {
                                udpSocket.send(packet); //Trying to retransmit
                                System.out.println("retx " + finalCurrentSeq);
                            } catch (IOException e) {
                                System.err.println("Retransmission failed: " + e.getMessage());
                            }
                        }
                    }, rtxTimeout, rtxTimeout);

                    //Waiting for ack of the current packet
                    while (!ackReceived) {
                        try {
                            byte[] ackBuffer = new byte[FtpSegment.MAX_SEGMENT_SIZE - FtpSegment.MAX_PAYLOAD_SIZE]; //Creating a buffer of the header size
                            DatagramPacket ackPacket = new DatagramPacket(ackBuffer, ackBuffer.length);
                            udpSocket.receive(ackPacket);

                            FtpSegment ackSegment = new FtpSegment(ackPacket);
                            //Checking if ack is received
                            if (ackSegment.getSeqNum() == currentSeq + 1) {
                                System.out.println("ack " + currentSeq);
                                ackReceived = true;
                                currentSeq++;
                                retransmissionTimer.cancel(); //Stop retransmissions for this segment
                                retransmissionTimer = new Timer(); //Reset the timer for the next segment
                            }
                        } catch (SocketTimeoutException e) {
                            //Timeout handler
                            System.out.println("timeout");
                            udpSocket.close();
                            retransmissionTimer.cancel();
                            return false;
                        }
                    }

                //Cleanup
                udpSocket.close();
                retransmissionTimer.cancel();
                return true;

            } catch (IOException e) {
                System.err.println("File transfer failed: " + e.getMessage());
                return false;
            }

        } catch (IOException e) {
            System.err.println("Connection setup failed: " + e.getMessage());
            return false;
        }
    }
}

