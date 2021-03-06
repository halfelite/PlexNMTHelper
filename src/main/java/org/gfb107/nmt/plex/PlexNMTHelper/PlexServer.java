package org.gfb107.nmt.plex.PlexNMTHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.ValidityException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;

public class PlexServer {
	private Logger logger = Logger.getLogger( PlexServer.class.getName() );
	private String name;
	private String address;
	private int port;

	private String clientId;
	private String clientName;

	private CloseableHttpClient client;

	private Element successResponse = null;

	public PlexServer( String address, int port, String name ) {
		this.address = address;
		this.port = port;
		this.name = name;

		successResponse = new Element( "Response" );
		successResponse.addAttribute( new Attribute( "code", "200" ) );
		successResponse.addAttribute( new Attribute( "status", "OK" ) );
	}

	public String getName() {
		return name;
	}

	public String getAddress() {
		return address;
	}

	public int getPort() {
		return port;
	}

	public void setClient( CloseableHttpClient client ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException {
		this.client = client;
	}

	public void setClientName( String clientName ) {
		this.clientName = clientName;
	}

	public void setClientId( String clientId ) {
		this.clientId = clientId;
	}

	private URIBuilder getBuilder() {
		return new URIBuilder().setScheme( "http" ).setHost( address ).setPort( port );
	}

	public Element sendCommand( URI uri ) throws ClientProtocolException, IOException, ValidityException, IllegalStateException, ParsingException {
		logger.fine( "Getting " + uri );
		HttpGet get = new HttpGet( uri );
		get.addHeader( "X-Plex-Client-Identifier", clientId );
		get.addHeader( "X-Plex-Device", "stb" );
		get.addHeader( "X-Plex-Device-Name", clientName );
		get.addHeader( "X-plex-Model", "Linux" );
		get.addHeader( "X-Plex-Provides", "player" );
		get.addHeader( "X-Plex-Product", NetworkedMediaTank.productName );
		get.addHeader( "X-Plex-Version", NetworkedMediaTank.productVersion );
		if ( token != null ) {
			get.addHeader( "X-Plex-Token", token );
		}

		Header[] headers = get.getAllHeaders();
		for ( Header header : headers ) {
			logger.finer( header.getName() + ": " + header.getValue() );
		}

		CloseableHttpResponse httpResponse = client.execute( get );
		HttpEntity entity = httpResponse.getEntity();

		Element response = successResponse;

		if ( entity != null && entity.getContentType().getValue().split( ";" )[0].equals( "text/xml" ) ) {
			response = new Builder().build( entity.getContent() ).getRootElement();
		} else {
			StatusLine statusLine = httpResponse.getStatusLine();
			response = new Element( "Response" );
			response.addAttribute( new Attribute( "code", Integer.toString( statusLine.getStatusCode() ) ) );
			response.addAttribute( new Attribute( "status", statusLine.getReasonPhrase() ) );
			StringBuilder sb = new StringBuilder();
			BufferedReader reader = new BufferedReader( new InputStreamReader( entity.getContent() ) );
			String line = null;
			while ( (line = reader.readLine()) != null ) {
				sb.append( line );
				sb.append( "\n" );
			}
			response.addAttribute( new Attribute( "content", sb.toString() ) );
		}

		logger.finer( "Response was " + response.toXML() );
		return response;
	}

	public PlayQueue getPlayQueue( String containerKey ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException, URISyntaxException {
		String[] parts = containerKey.split( "\\?" );
		String path = parts[0];
		String query = null;
		if ( parts.length > 1 ) {
			query = parts[1];
		}
		URIBuilder builder = getBuilder().setPath( path );

		if ( query != null ) {
			parts = query.split( "&" );
			for ( int i = 0; i < parts.length; i++ ) {
				String[] parm = parts[i].split( "=" );
				if ( parm.length > 1 ) {
					if ( !parm[0].equals( "own" ) ) {
						builder.addParameter( parm[0], parm[1] );
					}
				}
			}
		}

		Element element = sendCommand( builder.build() );

		PlayQueue queue = new PlayQueue( containerKey, Integer.parseInt( element.getAttributeValue( "playQueueSelectedItemOffset" ) ) );
		Elements tracks = element.getChildElements();
		for ( int t = 0; t < tracks.size(); ++t ) {
			Element trackElement = tracks.get( t );
			String type = trackElement.getAttributeValue( "type" );
			if ( type.equals( "movie" ) || type.equals( "episode" ) ) {
				queue.add( getVideo( containerKey, trackElement ) );
			} else if ( type.equals( "track" ) ) {
				queue.add( getTrack( containerKey, trackElement ) );
			}
		}
		return queue;
	}

	public Element updateTimeline( Video video ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException, URISyntaxException {
		URIBuilder builder = getBuilder().setPath( "/:/timeline" ).addParameter( "containerKey", video.getContainerKey() )
				.addParameter( "duration", Integer.toString( video.getDuration() ) ).addParameter( "guid", video.getGuid() )
				.addParameter( "key", video.getKey() ).addParameter( "ratingKey", video.getRatingKey() ).addParameter( "state", video.getState() )
				.addParameter( "time", Integer.toString( video.getCurrentTime() ) );

		return sendCommand( builder.build() );
	}

	public Element updateTimeline( Track audio ) throws ClientProtocolException, ValidityException, IllegalStateException, IOException,
			ParsingException, URISyntaxException {
		URIBuilder builder = getBuilder().setPath( "/:/timeline" ).addParameter( "containerKey", audio.getContainerKey() )

		.addParameter( "duration", Integer.toString( audio.getDuration() ) ).addParameter( "key", audio.getKey() )
				.addParameter( "ratingKey", audio.getRatingKey() ).addParameter( "state", audio.getState() )
				.addParameter( "time", Integer.toString( audio.getCurrentTime() ) );

		return sendCommand( builder.build() );
	}

	private String getPrefix() {
		return "http://" + address + ':' + port;
	}

	public Video getVideo( String containerKey, Element videoElement ) throws ClientProtocolException, ValidityException, IllegalStateException,
			IOException, ParsingException {
		String key = videoElement.getAttributeValue( "key" );
		String ratingKey = videoElement.getAttributeValue( "ratingKey" );
		String title = videoElement.getAttributeValue( "title" );
		String guid = videoElement.getAttributeValue( "guid" );
		int duration = 0;
		String temp = videoElement.getAttributeValue( "duration" );
		if ( temp != null ) {
			duration = Integer.parseInt( temp );
		}
		Element media = videoElement.getFirstChildElement( "Media" );
		Element part = media.getFirstChildElement( "Part" );
		String file = part.getAttributeValue( "file" );
		String httpFile = getPrefix() + part.getAttributeValue( "key" );

		return new Video( containerKey, key, ratingKey, title, guid, duration, file, httpFile );
	}

	public Track getTrack( String containerKey, Element trackElement ) {
		String ratingKey = trackElement.getAttributeValue( "ratingKey" );
		String key = trackElement.getAttributeValue( "key" );
		String title = trackElement.getAttributeValue( "title" );
		int duration = 0;
		String temp = trackElement.getAttributeValue( "duration" );
		if ( temp != null ) {
			duration = Integer.parseInt( temp );
		}
		Element part = trackElement.getFirstChildElement( "Media" ).getFirstChildElement( "Part" );
		String file = part.getAttributeValue( "key" );

		Track track = new Track( containerKey, key, ratingKey, title, file, duration );
		track.setPlayFile( getPrefix() + file );
		return track;
	}

	private String token = null;

	public void setToken( String token ) {
		this.token = token;
	}
}
