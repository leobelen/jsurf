/*
 *    Copyright 2008 Christian Stussak
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.mfo.jsurf;

import java.awt.Point;
import java.awt.geom.AffineTransform;
import java.awt.image.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.util.Properties;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.cli.*;

import javax.imageio.*;
import javax.imageio.metadata.*;
import javax.imageio.stream.*;

import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.Locale;

import de.mfo.jsurf.rendering.cpu.AntiAliasingPattern;
import de.mfo.jsurf.rendering.cpu.CPUAlgebraicSurfaceRenderer;
import de.mfo.jsurf.rendering.cpu.CPUAlgebraicSurfaceRenderer.AntiAliasingMode;
import de.mfo.jsurf.util.FileFormat;

public class Main {	
	
    public static final String jsurf_progname = "jsurf";

    static int size = 512;
    static int quality = 1;
    static AntiAliasingMode aam;
    static AntiAliasingPattern aap;
    
    static CPUAlgebraicSurfaceRenderer asr = new CPUAlgebraicSurfaceRenderer();

    static BufferedImage createBufferedImageFromRGB( ImgBuffer ib )
    {
        int w = ib.width;
        int h = ib.height;

        DirectColorModel colormodel = new DirectColorModel( 24, 0xff0000, 0xff00, 0xff );
        SampleModel sampleModel = colormodel.createCompatibleSampleModel( w, h );
        DataBufferInt data = new DataBufferInt( ib.rgbBuffer, w * h );
        WritableRaster raster = WritableRaster.createWritableRaster( sampleModel, data, new Point( 0, 0 ) );
        return new BufferedImage( colormodel, raster, false, null );
    }
    
    public static BufferedImage flipV( BufferedImage bi )
    {
    	AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
        tx.translate( 0, -bi.getHeight(null) );
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(bi, null);
    }
    
    public static OutputStream writeImage( OutputStream os, BufferedImage bi )
        throws Exception
    {
        ImageWriter writer = ImageIO.getImageWritersByFormatName( "png" ).next();

        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        ImageTypeSpecifier typeSpecifier = ImageTypeSpecifier.createFromBufferedImageType( BufferedImage.TYPE_INT_RGB );

        // add metadata
        // see https://docs.oracle.com/javase/7/docs/api/javax/imageio/metadata/doc-files/png_metadata.html
        // and https://www.w3.org/TR/PNG/#11textinfo
        IIOMetadata metadata = writer.getDefaultImageMetadata( typeSpecifier, writeParam );

        IIOMetadataNode textEntrySoftware = new IIOMetadataNode( "tEXtEntry" );
        textEntrySoftware.setAttribute( "keyword", "Software" );
        textEntrySoftware.setAttribute( "value", jsurf_progname + " version " + Main.class.getPackage().getImplementationVersion() + " (http://imaginary.org/program/jsurf)" );

        TimeZone tz = TimeZone.getTimeZone("UTC");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mmZ");
        df.setTimeZone(tz);
        Date theDate;
        // use either current time or the timestamp stored in the environment variable
        // SOURCE_DATE_EPOCH for reproducible builds
        if( System.getenv( "SOURCE_DATE_EPOCH" ) == null )
            theDate = new Date();
        else
            theDate = new Date( Long.parseLong( System.getenv( "SOURCE_DATE_EPOCH" ) ) * 1000 );

        IIOMetadataNode textEntryCreationTime = new IIOMetadataNode( "tEXtEntry" );
        textEntryCreationTime.setAttribute( "keyword", "CreationTime" );
        textEntryCreationTime.setAttribute( "value", df.format( theDate ) );

        IIOMetadataNode textEntrySource = new IIOMetadataNode( "tEXtEntry" );
        textEntrySource.setAttribute( "keyword", "Source" );
        textEntrySource.setAttribute( "value", jsurf_progname + " settings: --quality " + quality );

        IIOMetadataNode text = new IIOMetadataNode( "tEXt" );
        text.appendChild( textEntrySoftware );
        text.appendChild( textEntryCreationTime );
        text.appendChild( textEntrySource );

        IIOMetadataNode root = new IIOMetadataNode( "javax_imageio_png_1.0" );
        root.appendChild( text );

        metadata.mergeTree( "javax_imageio_png_1.0", root );

        // write the image and meta data
        ImageOutputStream image_os = ImageIO.createImageOutputStream( os );
        writer.setOutput( image_os );
        writer.write( metadata, new IIOImage( bi, null, metadata ), writeParam );
        image_os.close();

        return os;
    }

	/**
	 * @param args
	 */
	public static void main(String[] args) {  
		
    	int helper_width = 103;
    	String jsurf_filename = null;
    	String output_filename = null;
      boolean show_gui = false;

    	Options options = new Options();
    	
        options.addOption( OptionBuilder.withLongOpt( "help" ).withDescription( "display this help text and exit" ).create() );
		options.addOption( OptionBuilder.withLongOpt( "version" ).withDescription( "print program version and exit" ).create() );
    	options.addOption("s","size", true, "image width and height (default: " + size + ")");
    	options.addOption("q","quality",true,"quality of the rendering: 0 (low), 1 (medium, default), 2 (high), 3 (extreme)");
		options.addOption("o","output",true,"output PNG into this file (overrode by the 2nd argument if present)");
		options.addOption( OptionBuilder.withLongOpt( "gui" ).withDescription( "display rendering (overrides output options)" ).create() );

    	CommandLineParser parser = new PosixParser();
		HelpFormatter formatter = new HelpFormatter();
    	String cmd_line_syntax = jsurf_progname + " [options...] {jsurf_input|-} [png_output|-]\n\n";
    	String help_header = jsurf_progname + " is a renderer for real algebraic surfaces.\n" +
          jsurf_progname + " translates its own language script files (generally with extension '.jsurf') into Portable Network Graphics (PNG) files; " +
					"PNG is a raster graphics file format that supports lossless data compression.\n" +
    	    "If the output filename is not specified, the output is placed in a file of the same basename with a '.png' extension in the current working directory.\n" +
					"Either the input filename or the output filename can be '-' to request reading from stdin or writing to stdout, respectively. " +
					"Whenever the Graphic Unit Interface (GUI) is available, the gui option takes precedence over the output options, otherwise it is ignored. " +
					"That is, whenever the gui option is present (and available), the output is displayed in a window rather than written to a PNG file.\n" +
					"\n" ;
    	String help_footer = "";
    	try
    	{
    		CommandLine cmd = parser.parse( options, args );

			if( cmd.hasOption( "help" ) )
			{
    			formatter.printHelp( helper_width, cmd_line_syntax, help_header, options, help_footer );
    			return;
    		}

			if( cmd.hasOption( "version" ) )
			{
				System.out.println( Main.class.getPackage().getImplementationVersion() );
    			return;
    		}

    		if( cmd.hasOption( "output" ) )
    			output_filename = cmd.getOptionValue("output");

    		if( cmd.hasOption("size") )
    			size = Integer.parseInt( cmd.getOptionValue("size") );

    		if( cmd.hasOption("quality") )
    			quality = Integer.parseInt( cmd.getOptionValue( "quality" ) );
			switch( quality )
			{
			case 0:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_1x1;
		    	break;
			case 2:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_4x4;
		    	break;
			case 3:
		    	aam = AntiAliasingMode.SUPERSAMPLING;
		    	aap = AntiAliasingPattern.OG_4x4;
		    	break;
			case 1:
		    	aam = AntiAliasingMode.ADAPTIVE_SUPERSAMPLING;
		    	aap = AntiAliasingPattern.QUINCUNX;
			}

      if( cmd.hasOption( "gui" ) )
				show_gui = !java.awt.GraphicsEnvironment.isHeadless() ;

			if( cmd.getArgs().length == 0 || cmd.getArgs().length > 2 )
				{
    			formatter.printHelp( helper_width, cmd_line_syntax, help_header, options, help_footer );
    			System.exit( -1 );
    		}
			else
				{
          jsurf_filename = cmd.getArgs()[ 0 ];
					if (!show_gui)
						{
          		if( cmd.getArgs().length > 1 ) output_filename = cmd.getArgs()[ 1 ];
							if( output_filename == null )
								{
									output_filename = FilenameUtils.getBaseName( jsurf_filename ) + ".png" ;
								}
						}
					else
						{
							output_filename = null ;
						}
        }
    	}
    	catch( ParseException exp ) {
    	    System.out.println( "Unexpected exception:" + exp.getMessage() );
    	    System.exit( -1 );
    	}
    	catch( NumberFormatException nfe )
    	{
    		formatter.printHelp( cmd_line_syntax, help_header, options, help_footer );
    		System.exit( -1 );
    	}

    	try
    	{
    		Properties jsurf = new Properties();
    		if( jsurf_filename.equals( "-" ) )
    			jsurf.load( System.in );
    		else
    			jsurf.load( new FileReader( jsurf_filename ) );
    		FileFormat.load( jsurf, asr );	
    	}
    	catch( Exception e ) 
    	{
    		System.err.println( "Unable to read jsurf file " + jsurf_filename  );
    		e.printStackTrace();
    		System.exit( -2 );
    	}
    	
    	asr.setAntiAliasingMode( aam );
    	asr.setAntiAliasingPattern( aap );
    	
    	BufferedImage bi = null;
        try
        {
        	ImgBuffer ib = new ImgBuffer( size, size );
            asr.draw( ib.rgbBuffer, size, size );
        	bi = flipV( createBufferedImageFromRGB( ib ) );
        	
        }
        catch( Exception e )
        {
        	System.err.println( "An error occurred during rendering." );
    		e.printStackTrace();
    		System.exit( -3 );
        }
        
    	if( output_filename != null )
    	{
    		// save to file
    		try
    		{
    			OutputStream os;
    			if( output_filename.equals( "-" ) )
    				os = System.out;
    			else
    				os = new FileOutputStream( new File( output_filename ) );
                writeImage( os, bi );
                os.flush();
                os.close();
    		}
    		catch( Exception e )
    		{
    			System.err.println( "Unable to save to file  " + output_filename  );
        		e.printStackTrace();
        		System.exit( -4 );
    		}
    	}

        if( show_gui )
    	{
    		// display the image in a window
    		final String window_title =
					jsurf_progname + ": " +
					FilenameUtils.getBaseName( jsurf_filename ) +
					" (" + FilenameUtils.getFullPathNoEndSeparator( jsurf_filename ) + ")" ;
    		final BufferedImage window_image = bi;
    		SwingUtilities.invokeLater( new Runnable() {
    			public void run()
    			{
    				JFrame f = new JFrame( window_title );
    				f.setDefaultCloseOperation( JFrame.EXIT_ON_CLOSE );
    				f.getContentPane().add( new JLabel( new ImageIcon( window_image ) ) );
    				f.pack();
    				f.setResizable( false );
    				f.setVisible( true );
    			}
    		});
    	}
	}
}


class ImgBuffer
{
    public int[] rgbBuffer;
    public int width;
    public int height;

    public ImgBuffer( int w, int h ) { rgbBuffer = new int[ 3 * w * h ]; width = w; height = h; }
}
