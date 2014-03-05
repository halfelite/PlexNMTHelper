package org.gfb107.nmt.plex.PlexNMTHelper;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.ValidityException;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.simpleframework.http.Path;
import org.simpleframework.http.Query;
import org.simpleframework.http.Request;
import org.simpleframework.http.Response;
import org.simpleframework.http.Status;
import org.simpleframework.http.core.Container;
import org.simpleframework.http.core.ContainerServer;
import org.simpleframework.transport.connect.Connection;
import org.simpleframework.transport.connect.SocketConnection;

public class PlexNMTHelper implements Container {
	private static Logger logger = null;

	public static void main( String[] args ) {
		try {
			File logsDir = new File( "logs" );
			if ( !logsDir.exists() ) {
				logsDir.mkdirs();
			}
			System.setProperty( "java.util.logging.config.file", "logging.properties" );
			logger = Logger.getLogger( PlexNMTHelper.class.getName() );

			String fileName = "PlexNMTHelper.properties";

			if ( args.length == 1 ) {
				fileName = args[0];
			}

			logger.config( "Using property file " + fileName );
			File propertiesFile = new File( fileName );
			if ( !propertiesFile.canRead() ) {
				logger.severe( "Unable to read " + propertiesFile );
				return;
			}

			Properties properties = new Properties();
			FileReader reader = new FileReader( propertiesFile );
			properties.load( reader );
			reader.close();

			String nmtAddress = properties.getProperty( "nmtAddress" );
			if ( nmtAddress == null ) {
				logger.severe( "Missing property nmtAddress" );
				return;
			}

			String nmtName = properties.getProperty( "nmtName" );
			if ( nmtName == null ) {
				logger.severe( "Missing property nmtName" );
				return;
			}

			String temp = properties.getProperty( "port" );
			if ( temp == null ) {
				logger.severe( "Missing property port" );
				return;
			}
			int port = Integer.parseInt( temp );

			temp = properties.getProperty( "discoveryPort" );
			if ( temp == null ) {
				logger.severe( "Missing property discoveryPort" );
				return;
			}
			int discoveryPort = Integer.parseInt( temp );

			temp = properties.getProperty( "replacementConfig" );
			if ( temp == null ) {
				logger.severe( "Missing property replacementConfig" );
			}
			File replacementConfig = new File( temp );

			NetworkedMediaTank nmt = new NetworkedMediaTank( nmtAddress, nmtName );

			GDMDiscovery discovery = new GDMDiscovery( discoveryPort );
			PlexServer server = discovery.discover();

			String clientId = nmt.getMacAddress();

			server.setClientId( clientId );
			server.setClientName( nmt.getName() );

			GDMAnnouncer announcer = new GDMAnnouncer( nmtName, clientId, port );
			Thread announcerThread = new Thread( announcer );
			announcerThread.start();

			PlexNMTHelper helper = new PlexNMTHelper( nmt, port, server );
			helper.initReplacements( replacementConfig );

			@SuppressWarnings("resource")
			Connection connection = new SocketConnection( new ContainerServer( helper ) );
			connection.connect( new InetSocketAddress( port ) );

			logger.info( "Ready" );

			// connection.close();

		} catch ( Exception ex ) {
			ex.printStackTrace();
		}
	}

	private int listenPort = -1;

	private NetworkedMediaTank nmt;

	private PlexServer server;

	private Map< String, String > navigationMap = new HashMap< String, String >();
	private Map< String, String > playbackMap = new HashMap< String, String >();
	private Map< String, TimelineSubscriber > subscribers = new LinkedHashMap< String, TimelineSubscriber >();

	private String myAddress = getLocalHostLANAddress().getHostAddress();

	private CloseableHttpClient client = HttpClients.createDefault();

	private Element successResponse = null;

	private NowPlayingMonitor nowPlayingMonitor = null;

	public PlexNMTHelper( NetworkedMediaTank nmt, int port, PlexServer server ) throws IOException, ValidityException, IllegalStateException,
			ParsingException, InterruptedException {

		this.nmt = nmt;
		this.server = server;
		server.setClient( client );

		listenPort = port;

		nowPlayingMonitor = new NowPlayingMonitor( this, nmt );
		new Thread( nowPlayingMonitor ).start();

		navigationMap.put( "moveRight", "right" );
		navigationMap.put( "moveLeft", "left" );
		navigationMap.put( "moveUp", "up" );
		navigationMap.put( "moveDown", "down" );
		navigationMap.put( "select", "enter" );
		navigationMap.put( "back", "return" );
		navigationMap.put( "home", "home" );

		playbackMap.put( "play", "play" );
		playbackMap.put( "pause", "pause" );
		playbackMap.put( "stop", "stop" );
		playbackMap.put( "skipNext", "next" );
		playbackMap.put( "skipPrevious", "prev" );
		playbackMap.put( "repeat", "repeat" );

		successResponse = new Element( "Response" );
		successResponse.addAttribute( new Attribute( "code", "200" ) );
		successResponse.addAttribute( new Attribute( "status", "OK" ) );
	}

	public NetworkedMediaTank getNmt() {
		return nmt;
	}

	public PlexServer getServer() {
		return server;
	}

	public Map< String, TimelineSubscriber > getSubscribers() {
		return subscribers;
	}

	public CloseableHttpClient getClient() {
		return client;
	}

	public void handle( Request request, Response response ) {
		PrintStream body = null;
		try {
			body = response.getPrintStream();

			response.setValue( "Connection", "close" );
			response.setValue( "X-Plex-Client-Identifier", nmt.getMacAddress() );
			response.setValue( "Server", "PlexNMTHelper" );
			response.setContentType( "application/xml" );

			long time = System.currentTimeMillis();
			response.setDate( "Date", time );

			String message = process( request, response );

			if ( message == null ) {
				message = "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><Response code=\"200\" status=\"OK\" />";
			} else {
				logger.finer( "Responding with: " + message );
			}

			response.setContentLength( message.length() );
			body.print( message );

		} catch ( Exception e ) {
			e.printStackTrace();
			response.setStatus( Status.INTERNAL_SERVER_ERROR );
			response.setContentType( "text/plain" );
			String message = e.getMessage();
			response.setContentLength( message.length() );
			body.print( message );
		}
		body.close();
	}

	private String process( Request request, Response response ) throws ClientProtocolException, ValidityException, IllegalStateException,
			IOException, ParsingException, InterruptedException {
		Path path = request.getPath();
		String fullPath = path.getPath();
		String directory = path.getDirectory();
		String name = path.getName();

		Query query = request.getQuery();

		logger.fine( "Processing request for " + fullPath );
		for ( Map.Entry< String, String > entry : query.entrySet() ) {
			logger.finer( entry.getKey() + "=" + entry.getValue() );
		}
		if ( fullPath.equals( "/resources" ) ) {
			return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n<MediaContainer><Player platformVersion=\"2.00\" version=\"0.1\" protocolVersion=\"1\" machineIdentifier=\""
					+ nmt.getMacAddress()
					+ "\" protocolCapabilities=\"timeline,playback,navigation\" deviceClass=\"stb\" title=\""
					+ nmt.getName()
					+ "\" product=\"" + NetworkedMediaTank.productName + "\" /></MediaContainer>";
		} else if ( directory.equals( "/player/timeline/" ) ) {
			int port = query.getInteger( "port" );
			String commandId = query.get( "commandID" );
			String address = request.getClientAddress().getHostString();
			String clientId = request.getValue( "X-Plex-Client-Identifier" );

			if ( name.equals( "subscribe" ) ) {
				TimelineSubscriber subscriber = new TimelineSubscriber( commandId, address, port, server );
				subscriber.setClient( nmt.getMacAddress(), nmt.getName(), myAddress, listenPort );
				subscriber.setHttpClient( client );
				subscribers.put( clientId, subscriber );
			} else if ( name.equals( "unsubscribe" ) ) {
				subscribers.remove( clientId );
			}
			return null;
		} else if ( fullPath.equals( "/player/application/playMedia" ) ) {
			// From web client
			int viewOffset = query.getInteger( "viewOffset" ) / 1000;
			String file = query.get( "path" );
			nmt.sendKey( "stop", "playback" );
			playVideo( viewOffset, file );
			return null;
		} else if ( fullPath.equals( "/player/playback/playMedia" ) ) {
			// from mobile client
			nmt.sendKey( "stop", "playback" );

			int offset = query.getInteger( "offset" );
			String type = query.get( "type" );
			String commandId = query.get( "commandID" );
			String containerKey = query.get( "containerKey" );
			String key = query.get( "key" );
			String clientId = request.getValue( "X-Plex-Client-Identifier" );
			updateSubscriber( clientId, commandId );
			if ( type.equals( "video" ) ) {
				playVideo( offset, key );
			} else if ( type.equals( "music" ) ) {
				playAudio( offset, containerKey, key );
			}
			return null;
		} else if ( directory.equals( "/player/playback/" ) ) {
			String type = query.get( "type" );
			String commandId = query.get( "commandID" );
			String clientId = request.getValue( "X-Plex-Client-Identifier" );
			updateSubscriber( clientId, commandId );
			if ( name.equals( "seekTo" ) ) {
				int offset = query.getInteger( "offset" ) / 1000;
				return seek( offset, type );
			} else {
				nmt.sendKey( playbackMap.get( name ), "playback" );
				return null;
			}
		} else if ( directory.equals( "/player/navigation/" ) ) {
			return nmt.sendKey( navigationMap.get( name ), "flashlite" );
		} else {
			logger.warning( "Don't know what to do for " + fullPath );
			response.setStatus( Status.NOT_IMPLEMENTED );
			return "<Response status=\"Not Implemented\" code=\"501\" />";
		}
	}

	private void updateSubscriber( String clientId, String commandId ) {
		TimelineSubscriber subscriber = subscribers.get( clientId );
		if ( subscriber != null ) {
			subscriber.setCommandId( commandId );
		}
	}

	public void updateTimeline( Track audio, String state ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException {
		server.updateTimeline( audio, state );

		for ( TimelineSubscriber subscriber : subscribers.values() ) {
			subscriber.updateTimeline( audio, state );
		}
	}

	public void updateTimeline( Video video, String state ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException {
		server.updateTimeline( video, state );

		for ( TimelineSubscriber subscriber : subscribers.values() ) {

			subscriber.updateTimeline( video, state );
		}
	}

	private String seek( int offset, String type ) throws ClientProtocolException, IOException, ValidityException, IllegalStateException,
			ParsingException, InterruptedException {
		int seconds = offset % 60;
		offset = offset / 60;
		int minutes = offset % 60;
		int hours = offset / 60;

		String timestamp = String.format( "%02d:%02d:%02d", hours, minutes, seconds );

		nmt.sendCommand( "playback", "set_time_seek_vod", timestamp );

		return null;
	}

	private void playVideo( int time, String containerKey ) throws ClientProtocolException, IOException, ValidityException, IllegalStateException,
			ParsingException, InterruptedException {
		Video video = getVideoByKey( containerKey );
		video.setCurrentTime( time );
		nmt.play( video, time );
	}

	private void playAudio( int viewOffset, String containerKey, String trackKey ) throws IllegalStateException, InterruptedException {
		try {
			nmt.sendKey( "stop", "playback" );

			int trackIndex = -1;

			Track[] tracks = server.getTracks( containerKey );

			for ( int i = 0; i < tracks.length; ++i ) {
				Track track = tracks[i];
				nmt.insertInQueue( track );
				if ( track.getKey().equals( trackKey ) ) {
					track.setCurrentTime( viewOffset );
					trackIndex = i;
				} else {
					track.setCurrentTime( 0 );
				}
			}

			for ( int i = 0; i < trackIndex; ++i ) {
				nmt.sendKey( "next", "playback" );
			}
		} catch ( MalformedURLException e ) {
			e.printStackTrace();
		} catch ( IOException e ) {
			e.printStackTrace();
		} catch ( ValidityException e ) {
			e.printStackTrace();
		} catch ( ParsingException e ) {
			e.printStackTrace();
		}
	}

	private List< Replacement > replacements = new ArrayList< Replacement >();

	private void initReplacements( File replacementConfig ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException, InterruptedException {
		if ( replacementConfig.canRead() ) {
			try {
				Document doc = new Builder().build( replacementConfig );
				Elements replacementElements = doc.getRootElement().getFirstChildElement( "playback" ).getFirstChildElement( "replace_policy" )
						.getChildElements();
				for ( int i = 0; i < replacementElements.size(); ++i ) {
					Element replacement = replacementElements.get( i );
					String from = replacement.getAttributeValue( "from" ).replace( '\\', '/' );
					if ( !from.endsWith( "/" ) ) {
						from = from + '/';
					}

					String to = replacement.getAttributeValue( "to" );
					if ( !to.endsWith( "/" ) ) {
						to = to + '/';
					}

					String convertedTo = nmt.getConvertedPath( to );
					if ( !convertedTo.endsWith( "/" ) ) {
						convertedTo = convertedTo + '/';
					}
					logger.info( "Will map " + from + " to " + convertedTo );

					replacements.add( new Replacement( from, convertedTo ) );
				}
			} catch ( Exception e ) {
				logger.severe( "Error reading config.xml: " + e.getMessage() );
			}
		}

		if ( replacements.isEmpty() ) {
			logger.warning( "Warning, no path replacements have been configured." );
		}

		videoCache = new VideoCache( this );
		trackCache = new TrackCache( this );
	}

	public void fix( Video video ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException, ParsingException,
			InterruptedException {
		String file = video.getFile();
		file = file.replace( '\\', '/' );
		for ( Replacement replacement : replacements ) {
			if ( replacement.matches( file ) ) {
				video.setFile( replacement.convert( file ) );
				return;
			}
		}
		if ( file.startsWith( "//" ) ) {
			int slash = file.indexOf( '/', 2 );
			slash = file.indexOf( '/', slash + 1 );
			String share = "smb:" + file.substring( 0, slash + 1 );
			String convertedShare = nmt.getConvertedPath( share );
			if ( !convertedShare.endsWith( "/" ) ) {
				convertedShare = convertedShare + '/';
			}
			video.setFile( convertedShare + file.substring( slash + 1 ) );
		} else {
			logger.warning( "Video \"" + video.getTitle() + "\" will be played using HTTP streaming!" );
			video.setFile( video.getHttpFile() );
		}
	}

	private VideoCache videoCache = null;

	public Video getVideoByPath( String path ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException, InterruptedException {
		if ( videoCache == null ) {
			return null;
		}
		return videoCache.getByPath( path );
	}

	public Video getVideoByKey( String key ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException, ParsingException,
			InterruptedException {
		if ( videoCache == null ) {
			return null;
		}
		return videoCache.getByKey( key );
	}

	private TrackCache trackCache = null;

	public Track getTrack( String path ) throws ClientProtocolException, ValidityException, IllegalStateException, UnsupportedEncodingException,
			IOException, ParsingException {
		if ( trackCache == null ) {
			return null;
		}
		return trackCache.get( path );
	}

	private InetAddress getLocalHostLANAddress() throws UnknownHostException {
		try {
			InetAddress candidateAddress = null;
			// Iterate all NICs (network interface cards)...
			for ( Enumeration< NetworkInterface > ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements(); ) {
				NetworkInterface iface = ifaces.nextElement();
				// Iterate all IP addresses assigned to each card...
				for ( Enumeration< InetAddress > inetAddrs = iface.getInetAddresses(); inetAddrs.hasMoreElements(); ) {
					InetAddress inetAddr = inetAddrs.nextElement();
					if ( !inetAddr.isLoopbackAddress() ) {

						if ( inetAddr.isSiteLocalAddress() ) {
							// Found non-loopback site-local address. Return it
							// immediately...
							return inetAddr;
						} else if ( candidateAddress == null ) {
							// Found non-loopback address, but not necessarily
							// site-local.
							// Store it as a candidate to be returned if
							// site-local address is not subsequently found...
							candidateAddress = inetAddr;
							// Note that we don't repeatedly assign non-loopback
							// non-site-local addresses as candidates,
							// only the first. For subsequent iterations,
							// candidate will be non-null.
						}
					}
				}
			}
			if ( candidateAddress != null ) {
				// We did not find a site-local address, but we found some other
				// non-loopback address.
				// Server might have a non-site-local address assigned to its
				// NIC (or it might be running
				// IPv6 which deprecates the "site-local" concept).
				// Return this non-loopback candidate address...
				return candidateAddress;
			}
			// At this point, we did not find a non-loopback address.
			// Fall back to returning whatever InetAddress.getLocalHost()
			// returns...
			InetAddress jdkSuppliedAddress = InetAddress.getLocalHost();
			if ( jdkSuppliedAddress == null ) {
				throw new UnknownHostException( "The JDK InetAddress.getLocalHost() method unexpectedly returned null." );
			}
			return jdkSuppliedAddress;
		} catch ( Exception e ) {
			UnknownHostException unknownHostException = new UnknownHostException( "Failed to determine LAN address: " + e );
			unknownHostException.initCause( e );
			throw unknownHostException;
		}
	}

}