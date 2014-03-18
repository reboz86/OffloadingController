package src.controller;

import src.strategy.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.abdera.Abdera;
import org.apache.abdera.i18n.iri.IRI;
import org.apache.abdera.model.Content;
import org.apache.abdera.model.Document;
import org.apache.abdera.model.Entry;
import org.apache.abdera.model.Feed;
import org.apache.abdera.protocol.Response.ResponseType;
import org.apache.abdera.protocol.client.AbderaClient;
import org.apache.abdera.protocol.client.ClientResponse;
import org.apache.commons.codec.binary.Base64;

import org.apache.commons.cli.*;


public class OffloadingController {

	// Strings for CLI Options
	protected final static String serverAddress = "server";
	protected final static String serverPort = "port";
	protected final static String resource = "resource";
	protected final static String timelife = "timelife";
	protected final static String panic = "panic";

	protected static Options options = new Options();

	private static String feedSyncServerAddress;
	private static String resourcePath;
	private static int feedSyncServerPort; 
	protected static int timeLife;
	protected  static int panicZone;

	protected static Set<IRI> userIDs = new HashSet<IRI>();
	private static Map<IRI, Set<IRI>> msg_infected = new HashMap<IRI, Set<IRI>>();
	private static Map<IRI, Set<IRI>> msg_sane = new HashMap<IRI, Set<IRI>>();

	static Timer timer;

	@SuppressWarnings("serial")
	public static class HelpException extends Exception {}

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args)  throws Exception{
		
		// Read and parse args from stdin 
		options.addOption(new Option("h","help",false,"Print help"));
		initOptions();

		try {
			CommandLine cli = new PosixParser().parse(options, args);
			if ( cli.hasOption("help") )
				throw new HelpException();
			parseArgs(cli, cli.getArgs());
		} catch (ParseException e) {
			System.err.println(e);
			printHelp();
		} catch ( ArrayIndexOutOfBoundsException e){
			printHelp();
		} catch ( NumberFormatException nfe ){
			System.err.println(nfe);
			printHelp();
		} catch ( HelpException he ){
			printHelp();
		}

		// Initialize maps
		msg_infected.clear();
		msg_sane.clear();

		// Get The List of UEs awaiting content
		getUserList();

		// post Content and store the Content ID in the updateLink
		IRI updateLink = postContent( resourcePath);

		// Fill the map with content and IDs to diffuse 
		msg_sane.put(updateLink,userIDs); 
		msg_infected.put(updateLink, new HashSet<IRI>());

		// Execute offloading control  
		timer = new Timer();
		MessageUpdateTrigger reInjectionHandler = new MessageUpdateTrigger(updateLink);
		// scheduling the task at fixed rate
		timer.scheduleAtFixedRate(reInjectionHandler,new Date(),1000L);  

	}

	
	private static void getUserList()throws InterruptedException{

		// REST Client invocation
		Abdera abdera = new Abdera();
		AbderaClient abderaClient = new AbderaClient(abdera);

		// retrieve the list of IDs in the system
		System.out.println("Offloading Controller: connecting to "+ feedSyncServerAddress+":"+feedSyncServerPort+"...");

		ClientResponse resp = abderaClient.get(feedSyncServerAddress+":"+feedSyncServerPort+"/feedsync/rest/myfeeds/5/entries");

		// parse response
		if (resp.getType() == ResponseType.SUCCESS) {

			System.out.println("Offloading Controller: retrieved the user list");

			Document<Feed> doc = resp.getDocument();
			//IRI contentID=doc.getRoot().getId();

			System.out.println("Offloading Controller: "+ doc.getRoot().getEntries().size() + " UEs waiting the content.");
			if(!doc.getRoot().getEntries().isEmpty()){
				for(int i=0;i<doc.getRoot().getEntries().size();i++){
					userIDs.add(doc.getRoot().getEntries().get(i).getId()); // IDs of UEs are added in UserIDs
					//System.out.println(doc.getRoot().getEntries().get(i).getId());
				}
			} else {
				System.err.println("Error getting the list of users");
				throw new InterruptedException();
			}

		}else {
			System.err.println("Error getting the list of users");
			throw new InterruptedException();
		}

	}

	private static IRI postContent( String fileName ) throws Exception{ 

		Abdera abdera = new Abdera();
		AbderaClient abderaClient = new AbderaClient(abdera);

		File file = new File(fileName);
		loadFile(file);
		byte[] bytes = loadFile(file);
		byte[] encoded = Base64.encodeBase64(bytes);

		// create the new entry
		Entry newEntry = abdera.newEntry();
		//newEntry.setId("thales:example-content");
		newEntry.setTitle("Photo");
		newEntry.setUpdated(new Date());
		newEntry.setContent(new String(encoded), Content.Type.MEDIA);

		// post the content
		ClientResponse  resp= abderaClient.post(feedSyncServerAddress+":"+feedSyncServerPort+"/feedsync/rest/myfeeds/9/entries", newEntry);


		if (resp.getType() == ResponseType.SUCCESS) {
			IRI editUri = resp.getLocation();
			return editUri;
		}else {
			System.err.println("Error posting the content");
			throw new InterruptedException();
		}


	}

	private static byte[] loadFile(File file) throws IOException {
		InputStream is = new FileInputStream(file);

		long length = file.length();
		if (length > Integer.MAX_VALUE) {
			// File is too large
		}
		byte[] bytes = new byte[(int)length];

		int offset = 0;
		int numRead = 0;
		while (offset < bytes.length
				&& (numRead=is.read(bytes, offset, bytes.length-offset)) >= 0) {
			offset += numRead;
		}

		if (offset < bytes.length) {
			throw new IOException("Could not completely read file "+file.getName());
		}

		is.close();
		return bytes;
	}


	// This is a separate thread
	static class MessageUpdateTrigger extends TimerTask{

		//protected Set<Integer> present_ids = new HashSet<Integer>();
		long startTime;
		WhoToPush who_to_push;
		NumToPush num_to_push;
		IRI msgID;
		HashSet<IRI> sane, infected, ueToPush;


		public MessageUpdateTrigger(IRI msgID){

			//present_ids.clear();
			startTime = System.currentTimeMillis();
			num_to_push = (NumToPush) new InitialPush();
			who_to_push = (WhoToPush) new RandomWho();
			this.msgID = msgID;
		}

		@Override
		public void run() {

			System.out.println("Offloading Controller: Running");

			sane = new HashSet<IRI>( msg_sane.get(msgID));
			infected =  new HashSet<IRI>(msg_infected.get(msgID));
			ueToPush =  new HashSet<IRI>();


			// Retrieve the list of UEs that acked reception of the content	
			try {
				getAckList( msgID);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			// PANIC ZONE: send to all the uninfected UEs
			if ( panic() ){

				Iterator<IRI> i = sane.iterator();
				while ( i.hasNext() ){
					IRI to = i.next();

					infected.add(to);
					ueToPush.add(to);

					msg_sane.get(msgID).remove(to);
					msg_infected.get(msgID).add(to);

					i.remove();
				}

			} else {
				// Compute the number of copies to send at time t
				int n = num_to_push.numToPush( ( new Date()).getTime() - startTime, infected.size(),sane.size() );

				//System.out.println("Offloading Controller:numToPush: " +n );
				n = Math.min(n, sane.size());
				for(int i=0; i<n; ++i){

					//Compute to whom to send the copies
					IRI to = who_to_push.whoToPush(infected, sane);

					//System.out.println("Offloading Controller:whoToPush: " +to );
					if ( to == null )
						break;

					//update of the local Set (sane and infected) and overall Map (msg_sane and msg_infected)
					sane.remove(to);
					infected.add(to);

					msg_sane.get(msgID).remove(to);
					msg_infected.get(msgID).add(to);

					ueToPush.add(to);
				}
			}

			if(!ueToPush.isEmpty()){

				try {
					putDistributionList(msgID, ueToPush);
				} catch (InterruptedException e) {
					System.out.println("Interrupted !!!");
					e.printStackTrace();
				}
				System.out.println("Offloading Controller: Injection of "+ueToPush.size()+" copies after t = "+(( new Date()).getTime() - startTime)/1000);
			}else
				System.out.println("Offloading Controller: No injection after t="+(( new Date()).getTime() - startTime)/1000);

			// exiting when panic has been computed 
			if ( panic() ){
				System.out.println("Offloading Controller... Exiting");
				timer.cancel();
				timer.purge();
			}
		}

		private boolean panic(){ return (( new Date()).getTime() - startTime >= (OffloadingController.timeLife - OffloadingController.panicZone) * 1000 );	}

		private static void putDistributionList( IRI editUri, HashSet<IRI> idUes) throws InterruptedException{

			Abdera abdera = new Abdera();
			AbderaClient abderaClient = new AbderaClient(abdera);

			ClientResponse resp = abderaClient.get(editUri.toString());

			if (resp.getType() == ResponseType.SUCCESS) {

				Document<Entry> doc = resp.getDocument();
				//System.out.println("Offloading Controller: retrieved the required entry: "+ doc.getRoot().toString());

				doc.getRoot().addAuthor(idUes.toString().substring(1, idUes.toString().length() - 1));
				doc.getRoot().setUpdated(new Date());

				//System.out.println("putDistributionList put document: " +doc.getRoot().toString());
				abderaClient.put(editUri.toString(), doc.getRoot());


			}else {
				System.err.println("Error pushing the  distribution list of users");
				throw new InterruptedException();
			}

		}

		private static void getAckList( IRI editUri ) throws InterruptedException{

			Abdera abdera = new Abdera();
			AbderaClient abderaClient = new AbderaClient(abdera);

			//retrieve the content ID to look for
			String contentID =editUri.toASCIIString().substring(editUri.toASCIIString().lastIndexOf("/")+1);
			
			// Retrieve the entry with acked users for content with ID contentID
			ClientResponse resp = abderaClient.get(feedSyncServerAddress+":"+feedSyncServerPort+"/feedsync/rest/myfeeds/10/entries/"+contentID+"/ack");
			//System.out.println(feedSyncServerAddress+":"+feedSyncServerPort+"/feedsync/rest/myfeeds/10/entries/"+contentID);
			// parse response
			if (resp.getType() == ResponseType.SUCCESS ) {

				Document<Entry> doc = resp.getDocument();
				//IRI contentID=doc.getRoot().getId();

				
				if(doc.getRoot().getAuthor()!= null) {
					// we have some users that acked the content reception
					HashSet<IRI> infected, sane; 
					sane = new HashSet<IRI>( msg_sane.get(editUri));
					infected =  new HashSet<IRI>(msg_infected.get(editUri));

					// Extract Acked recipients
					StringTokenizer st ;
					if (doc.getRoot().getAuthor().getName() !=null){
						st = new StringTokenizer(doc.getRoot().getAuthor().getName().toString(), ",");

						while(st.hasMoreTokens()){
							String token = st.nextToken();
							infected.add(new IRI(token));
							sane.remove(new IRI(token));
						}
						// Insert updated SETs
						msg_sane.put(editUri, sane);
						msg_infected.put(editUri,infected);
						System.out.println("No users infected: "+infected.toString());
					}
				}
			}else {
				System.err.println("No users acked yet for content "+ contentID);
				//throw new InterruptedException();
			}
		}
	}

	private static void parseArgs(CommandLine cli, String[] args) 
			throws ParseException, ArrayIndexOutOfBoundsException, HelpException{

		System.out.println("parseArgs");

		feedSyncServerAddress = cli.getOptionValue(serverAddress,"http://192.168.0.1") ;
		feedSyncServerPort = Integer.parseInt(cli.getOptionValue(serverPort,"8083"));
		try{
			if (cli.hasOption(resource)){
				resourcePath = cli.getOptionValue(resource);
			}
			else
				throw new HelpException();

		} catch ( HelpException he ){
			printHelp();
		}

		timeLife = Integer.parseInt(cli.getOptionValue(timelife, "10"));
		panicZone = Integer.parseInt(cli.getOptionValue(panic, "1"));
	}

	private static void printHelp(){
		new HelpFormatter().printHelp("OffloadingController -resource <resource-path> [OPTIONS]" ,options);
		System.exit(1);
	}

	private static void initOptions() {
		options.addOption(new Option("a", serverAddress,true, "FeedSync Server Address"));
		options.addOption(new Option("p", serverPort,true, "FeedSync Server Port"));
		options.addOption(new Option("r", resource,true, "Resource to offload"));
		options.addOption(new Option("t", timelife,true, "Life time of resource"));
		options.addOption(new Option("panic", panic,true, "Panic Time"));
	}
}
