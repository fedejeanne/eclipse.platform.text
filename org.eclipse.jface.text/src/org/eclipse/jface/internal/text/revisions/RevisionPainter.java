/*******************************************************************************
 * Copyright (c) 2006 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jface.internal.text.revisions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.Platform;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.events.MouseTrackListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import org.eclipse.jface.text.Assert;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.information.IInformationProviderExtension2;
import org.eclipse.jface.text.revisions.IRevisionRulerColumnExtension;
import org.eclipse.jface.text.revisions.Revision;
import org.eclipse.jface.text.revisions.RevisionInformation;
import org.eclipse.jface.text.revisions.IRevisionRulerColumnExtension.RenderingMode;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.IAnnotationHover;
import org.eclipse.jface.text.source.IAnnotationHoverExtension;
import org.eclipse.jface.text.source.IAnnotationHoverExtension2;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.IAnnotationModelListener;
import org.eclipse.jface.text.source.IChangeRulerColumn;
import org.eclipse.jface.text.source.ILineDiffer;
import org.eclipse.jface.text.source.ILineRange;
import org.eclipse.jface.text.source.ISharedTextColors;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.IVerticalRulerColumn;
import org.eclipse.jface.text.source.LineRange;

import org.eclipse.jface.internal.text.JFaceTextUtil;
import org.eclipse.jface.internal.text.link.contentassist.HTMLTextPresenter;

/**
 * A strategy for painting the live annotate colors onto the vertical ruler column. It also manages
 * the revision hover.
 * 
 * @since 3.2
 */
public final class RevisionPainter {
	/** Tells whether this class is in debug mode. */
	private static boolean DEBUG= "true".equalsIgnoreCase(Platform.getDebugOption("org.eclipse.jface.text.source/debug/RevisionRulerColumn")); //$NON-NLS-1$//$NON-NLS-2$

	/**
	 * The annotations created to show a revision in the overview ruler.
	 */
	private static final class RevisionAnnotation extends Annotation {
		public RevisionAnnotation(String text) {
			super("org.eclipse.ui.workbench.texteditor.revisionAnnotation", false, text); //$NON-NLS-1$
		}
	}

	/**
	 * The color tool manages revision colors and computes shaded colors based on the relative age
	 * and author of a revision.
	 */
	private final class ColorTool {
		/**
		 * The average perceived intensity of a base color. 0 means black, 1 means white. A base
		 * revision color perceived as light such as yellow will be darkened, while colors perceived
		 * as dark such as blue will be lightened up.
		 */
		private static final float AVERAGE_INTENSITY= 0.5f;
		/**
		 * The maximum shading in [0, 1] - this is the shade that the most recent revision will
		 * receive.
		 */
		private static final float MAX_SHADING= 0.7f;
		/**
		 * The minimum shading in [0, 1] - this is the shade that the oldest revision will receive.
		 */
		private static final float MIN_SHADING= 0.2f;
		/**
		 * The shade for the focus boxes.
		 */
		private static final float FOCUS_COLOR_SHADING= 0.9f;

		/**
		 * A list of {@link Long}, storing the age of each revision in a sorted list.
		 */
		private List fRevisions;
		/**
		 * The stored shaded colors.
		 */
		private final Map fColors= new HashMap();
		/**
		 * The stored focus colors.
		 */
		private final Map fFocusColors= new HashMap();

		/**
		 * Sets the revision information, which is needed to compute the relative age of a revision.
		 * 
		 * @param info the new revision info, <code>null</code> for none.
		 */
		public void setInfo(RevisionInformation info) {
			fRevisions= null;
			fColors.clear();
			fFocusColors.clear();
			
			if (info == null)
				return;
			List revisions= new ArrayList();
			for (Iterator it= info.getRevisions().iterator(); it.hasNext();) {
				Revision revision= (Revision) it.next();
				revisions.add(new Long(computeAge(revision)));
			}
			Collections.sort(revisions);
			fRevisions= revisions;
		}

		private RGB adaptColor(Revision revision, boolean focus) {
			RGB rgb;
			float scale;
			if (fRenderingMode == IRevisionRulerColumnExtension.AGE) {
				int index= computeAgeIndex(revision);
				if (index == -1 || fRevisions.size() < 2) {
					rgb= getBackground().getRGB();
				} else {
					// gradient from intense red for most recent to faint yellow for oldest
					RGB[] gradient= Colors.palette(new RGB(0f, 1f, 1f), new RGB(60f, 0.3f, 1f), fRevisions.size());
					rgb= gradient[gradient.length - index - 1];
				}
				scale= 0.99f;
			} else if (fRenderingMode == IRevisionRulerColumnExtension.COMMITTER) {
				rgb= revision.getColor();
				rgb= Colors.adjustBrightness(rgb, AVERAGE_INTENSITY);
				scale= 0.6f;
			} else if (fRenderingMode == IRevisionRulerColumnExtension.COMMITTER_SHADED_BY_AGE) {
				rgb= revision.getColor();
				rgb= Colors.adjustBrightness(rgb, AVERAGE_INTENSITY);
				int index= computeAgeIndex(revision);
				int size= fRevisions.size();
				// relative age: newest is 0, oldest is 1
				// if there is only one revision, use an intermediate value to avoid extreme coloring
				if (index == -1 || size < 2)
					scale= 0.5f;
				else
					scale= (float) index / (size - 1);
			} else {
				Assert.isTrue(false);
				return null; // dummy
			}
			rgb= getShadedColor(rgb, scale, focus);
			return rgb;
		}

		private int computeAgeIndex(Revision revision) {
			long age= computeAge(revision);
			int index= fRevisions.indexOf(new Long(age));
			return index;
		}
		
		private RGB getShadedColor(RGB color, float scale, boolean focus) {
			Assert.isLegal(scale >= 0.0);
			Assert.isLegal(scale <= 1.0);
			RGB background= getBackground().getRGB();
			
			// focus coloring
			if (focus) {
				scale+= FOCUS_COLOR_SHADING;
				if (scale > 1) {
					background= new RGB(255 - background.red, 255 - background.green, 255 - background.blue);
					scale= 2 - scale;
				}
			}
			
			// normalize to lie within [MIN_SHADING, MAX_SHADING]
			// use more intense colors if the ruler is narrow (i.e. not showing line numbers)
			boolean makeIntense= getWidth() <= 15;
			float intensityShift= makeIntense ? 0.3f : 0f;
			float max= MAX_SHADING + intensityShift;
			float min= MIN_SHADING + intensityShift;
			scale= (max - min) * scale + min;

			return Colors.blend(background, color, scale);
		}

		private long computeAge(Revision revision) {
			return revision.getDate().getTime();
		}

		/**
		 * Returns the color for a revision based on relative age and author.
		 * 
		 * @param revision the revision
		 * @param focus <code>true</code> to return the focus color
		 * @return the color for a revision
		 */
		public RGB getColor(Revision revision, boolean focus) {
			Map map= focus ? fFocusColors : fColors;
			RGB color= (RGB) map.get(revision);
			if (color != null)
				return color;
			
			color= adaptColor(revision, focus);
			map.put(revision, color);
			return color;
		}
	}

	/**
	 * Handles all the mouse interaction in this line number ruler column.
	 */
	private class MouseHandler implements MouseListener, MouseMoveListener, MouseTrackListener, Listener {

		private ChangeRegion fMouseDownRegion;
		
		/*
		 * @see org.eclipse.swt.events.MouseListener#mouseUp(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseUp(MouseEvent e) {
			if (e.button == 1) {
				ChangeRegion upRegion= fFocusRegion;
				ChangeRegion downRegion= fMouseDownRegion;
				fMouseDownRegion= null;

				if (upRegion == downRegion) {
					Revision revision= upRegion == null ? null : upRegion.getRevision();
					handleRevisionSelected(revision);
				}
			}
		}

		/*
		 * @see org.eclipse.swt.events.MouseListener#mouseDown(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseDown(MouseEvent e) {
			if (e.button == 3)
				updateFocusRevision(null); // kill any focus as the ctx menu is going to show
			if (e.button == 1)
				fMouseDownRegion= fFocusRegion;
		}

		/*
		 * @see org.eclipse.swt.events.MouseListener#mouseDoubleClick(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseDoubleClick(MouseEvent e) {
		}

		/*
		 * @see org.eclipse.swt.widgets.Listener#handleEvent(org.eclipse.swt.widgets.Event)
		 */
		public void handleEvent(Event event) {
			Assert.isTrue(event.type == SWT.MouseWheel);
			handleMouseWheel(event);
		}

		/*
		 * @see org.eclipse.swt.events.MouseTrackListener#mouseEnter(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseEnter(MouseEvent e) {
			onEnter();
			updateFocusLine(toDocumentLineNumber(e.y));
		}

		/*
		 * @see org.eclipse.swt.events.MouseTrackListener#mouseExit(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseExit(MouseEvent e) {
			updateFocusLine(-1);
			onExit();
		}

		/*
		 * @see org.eclipse.swt.events.MouseTrackListener#mouseHover(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseHover(MouseEvent e) {
			onHover();
		}

		/*
		 * @see org.eclipse.swt.events.MouseMoveListener#mouseMove(org.eclipse.swt.events.MouseEvent)
		 */
		public void mouseMove(MouseEvent e) {
			updateFocusLine(toDocumentLineNumber(e.y));
		}
	}

	/**
	 * Internal listener class that will update the ruler when the underlying model changes.
	 */
	private class AnnotationListener implements IAnnotationModelListener {
		/*
		 * @see org.eclipse.jface.text.source.IAnnotationModelListener#modelChanged(org.eclipse.jface.text.source.IAnnotationModel)
		 */
		public void modelChanged(IAnnotationModel model) {
			fChangeRegions= null;
			postRedraw();
		}

	}

	/**
	 * The information control creator.
	 */
	private static final class HoverInformationControlCreator extends AbstractReusableInformationControlCreator {
		private boolean fIsFocusable;
		
		public HoverInformationControlCreator(boolean isFocusable) {
			fIsFocusable= isFocusable;
		}
		
		/*
		 * @see org.eclipse.jface.internal.text.revisions.AbstractReusableInformationControlCreator#doCreateInformationControl(org.eclipse.swt.widgets.Shell)
		 */
		protected IInformationControl doCreateInformationControl(Shell parent) {
			int style= fIsFocusable ? SWT.V_SCROLL | SWT.H_SCROLL : SWT.NONE;
			
			if (BrowserInformationControl.isAvailable(parent)) {
	            final int shellStyle= SWT.TOOL | (fIsFocusable ? SWT.RESIZE : SWT.NO_TRIM);
	            return new BrowserInformationControl(parent, shellStyle, style, null);
            }
			return new DefaultInformationControl(parent, style, new HTMLTextPresenter());
		}
	}
	
	private static final String fgStyleSheet= "/* Font definitions */\n" + //$NON-NLS-1$
		"body, h1, h2, h3, h4, h5, h6, p, table, td, caption, th, ul, ol, dl, li, dd, dt {font-family: sans-serif; font-size: 9pt }\n" + //$NON-NLS-1$ 
		"pre				{ font-family: monospace; font-size: 9pt }\n" + //$NON-NLS-1$
		"\n" + //$NON-NLS-1$
		"/* Margins */\n" + //$NON-NLS-1$
		"body	     { overflow: auto; margin-top: 0; margin-bottom: 4; margin-left: 3; margin-right: 0 }\n" + //$NON-NLS-1$ 
		"h1           { margin-top: 5; margin-bottom: 1 }	\n" + //$NON-NLS-1$
		"h2           { margin-top: 25; margin-bottom: 3 }\n" + //$NON-NLS-1$
		"h3           { margin-top: 20; margin-bottom: 3 }\n" + //$NON-NLS-1$
		"h4           { margin-top: 20; margin-bottom: 3 }\n" + //$NON-NLS-1$
		"h5           { margin-top: 0; margin-bottom: 0 }\n" + //$NON-NLS-1$
		"p            { margin-top: 10px; margin-bottom: 10px }\n" + //$NON-NLS-1$
		"pre	         { margin-left: 6 }\n" + //$NON-NLS-1$
		"ul	         { margin-top: 0; margin-bottom: 10 }\n" + //$NON-NLS-1$ 
		"li	         { margin-top: 0; margin-bottom: 0 } \n" + //$NON-NLS-1$
		"li p	     { margin-top: 0; margin-bottom: 0 } \n" + //$NON-NLS-1$
		"ol	         { margin-top: 0; margin-bottom: 10 }\n" + //$NON-NLS-1$
		"dl	         { margin-top: 0; margin-bottom: 10 }\n" + //$NON-NLS-1$
		"dt	         { margin-top: 0; margin-bottom: 0; font-weight: bold }\n" + //$NON-NLS-1$ 
		"dd	         { margin-top: 0; margin-bottom: 0 }\n" + //$NON-NLS-1$
		"\n" + //$NON-NLS-1$
		"/* Styles and colors */\n" + //$NON-NLS-1$
		"a:link	     { color: #0000FF }\n" + //$NON-NLS-1$
		"a:hover	     { color: #000080 }\n" + //$NON-NLS-1$
		"a:visited    { text-decoration: underline }\n" + //$NON-NLS-1$
		"h4           { font-style: italic }\n" + //$NON-NLS-1$ 
		"strong	     { font-weight: bold }\n" + //$NON-NLS-1$
		"em	         { font-style: italic }\n" + //$NON-NLS-1$
		"var	         { font-style: italic }\n" + //$NON-NLS-1$
		"th	         { font-weight: bold }\n" + //$NON-NLS-1$
		""; //$NON-NLS-1$
	
	/**
	 * The revision hover displays information about the currently selected revision.
	 */
	private final class RevisionHover implements IAnnotationHover, IAnnotationHoverExtension, IAnnotationHoverExtension2, IInformationProviderExtension2 {
		
		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHover#getHoverInfo(org.eclipse.jface.text.source.ISourceViewer,
		 *      int)
		 */
		public String getHoverInfo(ISourceViewer sourceViewer, int lineNumber) {
			Object info= getHoverInfo(sourceViewer, getHoverLineRange(sourceViewer, lineNumber), 0);
			return info == null ? null : info.toString();
		}

		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getHoverControlCreator()
		 */
		public IInformationControlCreator getHoverControlCreator() {
			return new HoverInformationControlCreator(false);
		}

		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#canHandleMouseCursor()
		 */
		public boolean canHandleMouseCursor() {
			return false;
		}

		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension2#canHandleMouseWheel()
		 */
		public boolean canHandleMouseWheel() {
			return true;
		}

		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getHoverInfo(org.eclipse.jface.text.source.ISourceViewer,
		 *      org.eclipse.jface.text.source.ILineRange, int)
		 */
		public Object getHoverInfo(ISourceViewer sourceViewer, ILineRange lineRange, int visibleNumberOfLines) {
			ChangeRegion region= getChangeRegion(lineRange.getStartLine());
			Object info= region == null ? null : region.getRevision().getHoverInfo();
			if (info instanceof String) {
				info= addCSSToHTMLFragment((String) info);
			}
			return info;
		}

		/**
		 * Adds a HTML header and CSS info if <code>html</code> is only an HTML fragment (has no
		 * &lt;html&gt; section).
		 * 
		 * @param html the html / text produced by a revision
		 * @return modified html
		 */
		private Object addCSSToHTMLFragment(String html) {
			int max= Math.min(100, html.length());
			if (html.substring(0, max).indexOf("<html>") != -1) //$NON-NLS-1$
				// there is already a header
				return html;
			
			StringBuffer info= new StringBuffer(512 + html.length());
			HTMLPrinter.insertPageProlog(info, 0, fgStyleSheet);
			info.append(html);
			HTMLPrinter.addPageEpilog(info);
			return info.toString();
		}

		/*
		 * @see org.eclipse.jface.text.source.IAnnotationHoverExtension#getHoverLineRange(org.eclipse.jface.text.source.ISourceViewer,
		 *      int)
		 */
		public ILineRange getHoverLineRange(ISourceViewer viewer, int lineNumber) {
			ChangeRegion region= getChangeRegion(lineNumber);
			return region == null ? null : new LineRange(lineNumber, 1);
		}

		/*
         * @see org.eclipse.jface.text.information.IInformationProviderExtension2#getInformationPresenterControlCreator()
         */
        public IInformationControlCreator getInformationPresenterControlCreator() {
			return new HoverInformationControlCreator(true);
        }
	}

	/* Listeners and helpers. */

	/** The shared color provider. */
	private final ISharedTextColors fSharedColors;
	/** The color tool. */
	private final ColorTool fColorTool= new ColorTool();
	/** The mouse handler. */
	private final MouseHandler fMouseHandler= new MouseHandler();
	/** The hover. */
	private final RevisionHover fHover= new RevisionHover();
	/** The annotation listener. */
	private final AnnotationListener fAnnotationListener= new AnnotationListener();
	/** The selection provider. */
	private final RevisionSelectionProvider fRevisionSelectionProvider= new RevisionSelectionProvider(this);

	/* The context - column and viewer we are connected to. */

	/** The vertical ruler column that delegates painting to this painter. */
	private final IVerticalRulerColumn fColumn;
	/** The parent ruler. */
	private CompositeRuler fParentRuler;
	/** The column's control, typically a {@link Canvas}, possibly <code>null</code>. */
	private Control fControl;
	/** The text viewer that the column is attached to. */
	private ITextViewer fViewer;
	/** The viewer's text widget. */
	private StyledText fWidget;

	/* The models we operate on. */

	/** The revision model object. */
	private RevisionInformation fRevisionInfo;
	/** The line differ. */
	private ILineDiffer fLineDiffer= null;
	/** The annotation model. */
	private IAnnotationModel fAnnotationModel= null;
	/** The background color, possibly <code>null</code>. */
	private Color fBackground;

	/* Cache. */

	/** The cached list of change regions adapted to quick diff. */
	private ArrayList fChangeRegions= null;
	/** The annotations created for the overview ruler temporary display. */
	private List fAnnotations= new ArrayList();

	/* State */

	/** The current focus line, -1 for none. */
	private int fFocusLine= -1;
	/** The current focus region, <code>null</code> if none. */
	private ChangeRegion fFocusRegion= null;
	/** The current focus revision, <code>null</code> if none. */
	private Revision fFocusRevision= null;
	/** <code>true</code> if the mouse wheel handler is installed, <code>false</code> otherwise. */
	private boolean fWheelHandlerInstalled= false;
	/**
	 * <code>true</code> if the overview annotations are displayed, <code>false</code>
	 * otherwise.
	 */
	private boolean fIsOverviewShowing= false;
	/**
	 * The revision rendering mode.
	 */
	private RenderingMode fRenderingMode= IRevisionRulerColumnExtension.COMMITTER_SHADED_BY_AGE;
	/**
	 * The required with in characters.
	 * @since 3.3
	 */
	private int fRequiredWidth= -1;
	/**
	 * The width of the revision field in chars to compute {@link #fAuthorInset} from.
	 * @since 3.3
	 */
	private int fRevisionIdChars= 0;
	/**
	 * <code>true</code> to show revision ids, <code>false</code> otherwise.
	 * @since 3.3
	 */
	private boolean fShowRevision= false;
	/**
	 * <code>true</code> to show committers, <code>false</code> otherwise.
	 * @since 3.3
	 */
	private boolean fShowAuthor= false;
	/**
	 * The author inset in pixels for when author *and* revision id are shown.
	 * @since 3.3
	 */
	private int fAuthorInset;
	/**
	 * The remembered ruler width (as changing the ruler width triggers recomputation of the colors.
	 * @since 3.3
	 */
	private int fLastWidth= -1;

	/**
	 * Creates a new revision painter for a vertical ruler column.
	 * 
	 * @param column the column that will delegate{@link #paint(GC, ILineRange) painting} to the
	 *        newly created painter.
	 * @param sharedColors a shared colors object to store shaded colors in
	 */
	public RevisionPainter(IVerticalRulerColumn column, ISharedTextColors sharedColors) {
		Assert.isLegal(column != null);
		Assert.isLegal(sharedColors != null);
		fColumn= column;
		fSharedColors= sharedColors;
	}

	/**
	 * Sets the revision information to be drawn and triggers a redraw.
	 * 
	 * @param info the revision information to show, <code>null</code> to draw none
	 */
	public void setRevisionInformation(RevisionInformation info) {
		if (fRevisionInfo != info) {
			fRequiredWidth= -1;
			fRevisionIdChars= 0;
			fRevisionInfo= info;
			fChangeRegions= null;
			updateFocusRegion(null);
			fColorTool.setInfo(info);
			postRedraw();
		}
	}
	
	/**
	 * Changes the rendering mode and triggers redrawing if needed.
	 *  
	 * @param renderingMode the rendering mode
	 * @since 3.3
	 */
	public void setRenderingMode(RenderingMode renderingMode) {
		Assert.isLegal(renderingMode != null);
		if (fRenderingMode != renderingMode) {
			fRenderingMode= renderingMode;
			fColorTool.setInfo(fRevisionInfo);
			postRedraw();
		}
	}

	/**
	 * Sets the background color.
	 * 
	 * @param background the background color, <code>null</code> for the platform's list
	 *        background
	 */
	public void setBackground(Color background) {
		fBackground= background;
	}

	/**
	 * Sets the parent ruler - the delegating column must call this method as soon as it creates its
	 * control.
	 * 
	 * @param parentRuler the parent ruler
	 */
	public void setParentRuler(CompositeRuler parentRuler) {
		fParentRuler= parentRuler;
	}

	/**
	 * Delegates the painting of the quick diff colors to this painter. The painter will draw the
	 * color boxes onto the passed {@link GC} for all model (document) lines in
	 * <code>visibleModelLines</code>.
	 * 
	 * @param gc the {@link GC} to draw onto
	 * @param visibleLines the lines (in document offsets) that are currently (perhaps only
	 *        partially) visible
	 */
	public void paint(GC gc, ILineRange visibleLines) {
		connectIfNeeded();
		if (!isConnected())
			return;

		// compute the horizontal indent of the committer for the case that we show both committer
		// and author
		if (fShowAuthor && fShowRevision) {
			char[] string= new char[fRevisionIdChars + 1];
			Arrays.fill(string, '9');
			if (string.length > 1) {
				string[0]= '.';
				string[1]= ' ';
			}
			fAuthorInset= gc.stringExtent(new String(string)).x;
		}
		
		// recompute colors (show intense colors if ruler is narrow)
		int width= getWidth();
		if (width != fLastWidth) {
			fColorTool.setInfo(fRevisionInfo);
			fLastWidth= width;
		}
		
		// draw change regions
		List/* <ChangeRegion> */changes= getChangeRegions(visibleLines);
		for (Iterator it= changes.iterator(); it.hasNext();) {
			ChangeRegion region= (ChangeRegion) it.next();
			paintChangeRegion(region, gc);
		}
	}

	/**
	 * Ensures that the column is fully instantiated, i.e. has a control, and that the viewer is
	 * visible.
	 */
	private void connectIfNeeded() {
		if (isConnected() || fParentRuler == null)
			return;

		fViewer= fParentRuler.getTextViewer();
		if (fViewer == null)
			return;

		fWidget= fViewer.getTextWidget();
		if (fWidget == null)
			return;

		fControl= fColumn.getControl();
		if (fControl == null)
			return;

		fControl.addMouseTrackListener(fMouseHandler);
		fControl.addMouseMoveListener(fMouseHandler);
		fControl.addMouseListener(fMouseHandler);
		fControl.addDisposeListener(new DisposeListener() {
			/*
			 * @see org.eclipse.swt.events.DisposeListener#widgetDisposed(org.eclipse.swt.events.DisposeEvent)
			 */
			public void widgetDisposed(DisposeEvent e) {
				handleDispose();
			}
		});
		
		fRevisionSelectionProvider.install(fViewer);
	}

	/**
	 * Returns <code>true</code> if the column is fully connected.
	 * 
	 * @return <code>true</code> if the column is fully connected, false otherwise
	 */
	private boolean isConnected() {
		return fControl != null;
	}

	/**
	 * Sets the annotation model.
	 * 
	 * @param model the annotation model, possibly <code>null</code>
	 * @see IVerticalRulerColumn#setModel(IAnnotationModel)
	 */
	public void setModel(IAnnotationModel model) {
		IAnnotationModel diffModel;
		if (model instanceof IAnnotationModelExtension)
			diffModel= ((IAnnotationModelExtension) model).getAnnotationModel(IChangeRulerColumn.QUICK_DIFF_MODEL_ID);
		else
			diffModel= model;

		setDiffer(diffModel);
		setAnnotationModel(model);
	}

	/**
	 * Sets the annotation model.
	 * 
	 * @param model the annotation model.
	 */
	private void setAnnotationModel(IAnnotationModel model) {
		if (fAnnotationModel != model)
			fAnnotationModel= model;
	}

	/**
	 * Sets the line differ.
	 * 
	 * @param differ the line differ
	 */
	private void setDiffer(IAnnotationModel differ) {
		if (differ instanceof ILineDiffer) {
			if (fLineDiffer != differ) {
				if (fLineDiffer != null)
					((IAnnotationModel) fLineDiffer).removeAnnotationModelListener(fAnnotationListener);
				fLineDiffer= (ILineDiffer) differ;
				if (fLineDiffer != null)
					((IAnnotationModel) fLineDiffer).addAnnotationModelListener(fAnnotationListener);
			}
		}
	}

	/**
	 * Disposes of the painter's resources.
	 */
	private void handleDispose() {
		updateFocusLine(-1);

		if (fLineDiffer != null) {
			((IAnnotationModel) fLineDiffer).removeAnnotationModelListener(fAnnotationListener);
			fLineDiffer= null;
		}
		
		fRevisionSelectionProvider.uninstall();
	}

	/**
	 * Paints a single change region onto <code>gc</code>.
	 * 
	 * @param region the change region to paint
	 * @param gc the {@link GC} to paint on
	 */
	private void paintChangeRegion(ChangeRegion region, GC gc) {
		Revision revision= region.getRevision();
		gc.setBackground(lookupColor(revision, false));
		Color foreground= gc.getForeground();
		if (revision == fFocusRevision)
			gc.setForeground(lookupColor(revision, true));

		List ranges= region.getAdjustedRanges();
		for (Iterator it= ranges.iterator(); it.hasNext();) {
			ILineRange range= (ILineRange) it.next();
			ILineRange widgetRange= modelLinesToWidgetLines(range);
			if (widgetRange == null)
				return;
			Rectangle box= computeBoxBounds(widgetRange);

			if (revision == fFocusRevision)
				paintHighlight(gc, box);
			else
				gc.fillRectangle(box);
			
			boolean drawText= fShowAuthor || fShowRevision;
			if (drawText) {
				gc.setForeground(foreground);
				int widgetLine= range.getStartLine();
				
				int indentation= 1;
				int baselineBias= getBaselineBias(gc, widgetLine);
				if (fShowAuthor && fShowRevision) {
					gc.drawString(revision.getId(), indentation, box.y + baselineBias, true);
					gc.drawString(revision.getAuthor(), fAuthorInset, box.y + baselineBias, true);
				} else if (fShowAuthor) {
					gc.drawString(revision.getAuthor(), indentation, box.y + baselineBias, true);
				} else if (fShowRevision) {
					gc.drawString(revision.getId(), indentation, box.y + baselineBias, true);
				}
			}

		}
	}

	/**
	 * Returns the difference between the baseline of the widget and the
	 * baseline as specified by the font for <code>gc</code>. When drawing
	 * line numbers, the returned bias should be added to obtain text lined up
	 * on the correct base line of the text widget.
	 *
	 * @param gc the <code>GC</code> to get the font metrics from
	 * @param widgetLine the widget line
	 * @return the baseline bias to use when drawing text that is lined up with
	 *         <code>fCachedTextWidget</code>
	 * @since 3.3
	 */
	private int getBaselineBias(GC gc, int widgetLine) {
		/*
		 * https://bugs.eclipse.org/bugs/show_bug.cgi?id=62951
		 * widget line height may be more than the font height used for the
		 * line numbers, since font styles (bold, italics...) can have larger
		 * font metrics than the simple font used for the numbers.
		 */
		int offset= fWidget.getOffsetAtLine(widgetLine);
		int widgetBaseline= fWidget.getBaseline(offset);
		
		FontMetrics fm = gc.getFontMetrics();
		int fontBaseline = fm.getAscent() + fm.getLeading();
		int baselineBias= widgetBaseline - fontBaseline;
		return Math.max(0, baselineBias);
	}
	
	/**
	 * Paints the box for highlighted regions.
	 * 
	 * @param gc the {@link GC} to draw on
	 * @param box the box to draw
	 */
	private void paintHighlight(GC gc, Rectangle box) {
		gc.fillRectangle(box); // background
		gc.drawRectangle(box.x, box.y, box.width - 1, box.height - 1); // highlight box
	}

	/**
	 * Looks up the color for a certain revision.
	 * 
	 * @param revision the revision to get the color for
	 * @param focus <code>true</code> if it is the focus revision
	 * @return the color for the revision
	 */
	private Color lookupColor(Revision revision, boolean focus) {
		return fSharedColors.getColor(fColorTool.getColor(revision, focus));
	}

	/**
	 * Returns the change region that contains the given line in one of its adjusted line ranges, or
	 * <code>null</code> if there is none.
	 * 
	 * @param line the line of interest
	 * @return the corresponding <code>ChangeRegion</code> or <code>null</code>
	 */
	private ChangeRegion getChangeRegion(int line) {
		List regions= getRegionCache();

		if (regions.isEmpty() || line == -1)
			return null;

		for (Iterator it= regions.iterator(); it.hasNext();) {
			ChangeRegion region= (ChangeRegion) it.next();
			if (contains(region.getAdjustedRanges(), line))
				return region;
		}

		// line may be right after the last region
		ChangeRegion lastRegion= (ChangeRegion) regions.get(regions.size() - 1);
		if (line == end(lastRegion.getAdjustedCoverage()))
			return lastRegion;
		return null;
	}

	/**
	 * Returns the sublist of all <code>ChangeRegion</code>s that intersect with the given lines.
	 * 
	 * @param lines the model based lines of interest
	 * @return elementType: ChangeRegion
	 */
	private List getChangeRegions(ILineRange lines) {
		List regions= getRegionCache();

		// return the interesting subset
		int end= end(lines);
		int first= -1, last= -1;
		for (int i= 0; i < regions.size(); i++) {
			ChangeRegion region= (ChangeRegion) regions.get(i);
			int coverageEnd= end(region.getAdjustedCoverage());
			if (first == -1 && coverageEnd > lines.getStartLine())
				first= i;
			if (first != -1 && coverageEnd > end) {
				last= i;
				break;
			}
		}
		if (first == -1)
			return Collections.EMPTY_LIST;
		if (last == -1)
			last= regions.size() - 1; // bottom index may be one too much

		return regions.subList(first, last + 1);
	}

	/**
	 * Gets all change regions of the revisions in the revision model and adapts them to the current
	 * quick diff information. The list is cached.
	 * 
	 * @return the list of all change regions, with diff information applied
	 */
	private List getRegionCache() {
		if (fChangeRegions == null && fRevisionInfo != null) {
			ArrayList regions= new ArrayList();
			// flatten
			for (Iterator revisions= fRevisionInfo.getRevisions().iterator(); revisions.hasNext();) {
				Revision revision= (Revision) revisions.next();
				regions.addAll(revision.getRegions());
			}

			// sort
			Collections.sort(regions, new Comparator() {
				public int compare(Object o1, Object o2) {
					ChangeRegion r1= (ChangeRegion) o1;
					ChangeRegion r2= (ChangeRegion) o2;

					// sort order is unaffected by diff information
					return r1.getOriginalRange().getStartLine() - r2.getOriginalRange().getStartLine();
				}
			});

			if (fLineDiffer != null)
				new DiffApplier().applyDiff(regions, fLineDiffer, fViewer.getDocument().getNumberOfLines());

			fChangeRegions= regions;
		}

		if (fChangeRegions == null)
			return Collections.EMPTY_LIST;

		return fChangeRegions;
	}

	/**
	 * Returns <code>true</code> if <code>range</code> contains <code>line</code>. A line is
	 * not contained in a range if it is the range's exclusive end line.
	 * 
	 * @param range the range to check whether it contains <code>line</code>
	 * @param line the line the line
	 * @return <code>true</code> if <code>range</code> contains <code>line</code>,
	 *         <code>false</code> if not
	 */
	private static boolean contains(ILineRange range, int line) {
		return range.getStartLine() <= line && end(range) > line;
	}

	/**
	 * Returns <code>true</code> if any of the line ranges
	 * {@link #contains(ILineRange, int) contains} <code>line</code>.
	 * 
	 * @param ranges a list of {@link ILineRange}s
	 * @param line a line
	 * @return <code>true</code> if <code>line</code> is contained in any of the passed
	 *         <code>ranges</code>
	 */
	private static boolean contains(List ranges, int line) {
		for (Iterator it= ranges.iterator(); it.hasNext();) {
			ILineRange range= (ILineRange) it.next();
			if (contains(range, line))
				return true;
		}
		return false;
	}

	/**
	 * Computes the end index of a line range.
	 * 
	 * @param range a line range
	 * @return the last line (exclusive) of <code>range</code>
	 */
	private static int end(ILineRange range) {
		return range.getStartLine() + range.getNumberOfLines();
	}

	/**
	 * Returns the visible extent of a document line range in widget lines.
	 * 
	 * @param range the document line range
	 * @return the visible extent of <code>range</code> in widget lines
	 */
	private ILineRange modelLinesToWidgetLines(ILineRange range) {
		int widgetStartLine= -1;
		int widgetEndLine= -1;
		if (fViewer instanceof ITextViewerExtension5) {
			ITextViewerExtension5 extension= (ITextViewerExtension5) fViewer;
			int modelEndLine= end(range);
			for (int modelLine= range.getStartLine(); modelLine < modelEndLine; modelLine++) {
				int widgetLine= extension.modelLine2WidgetLine(modelLine);
				if (widgetLine != -1) {
					if (widgetStartLine == -1)
						widgetStartLine= widgetLine;
					widgetEndLine= widgetLine;
				}
			}
		} else {
			IRegion region= fViewer.getVisibleRegion();
			IDocument document= fViewer.getDocument();
			try {
				int visibleStartLine= document.getLineOfOffset(region.getOffset());
				int visibleEndLine= document.getLineOfOffset(region.getOffset() + region.getLength());
				widgetStartLine= Math.max(0, range.getStartLine() - visibleStartLine);
				widgetEndLine= Math.min(visibleEndLine, end(range) - 1);
			} catch (BadLocationException x) {
				x.printStackTrace();
				// ignore and return null
			}
		}
		if (widgetStartLine == -1 || widgetEndLine == -1)
			return null;
		return new LineRange(widgetStartLine, widgetEndLine - widgetStartLine + 1);
	}

	/**
	 * Returns the revision hover.
	 * 
	 * @return the revision hover
	 */
	public IAnnotationHover getHover() {
		return fHover;
	}

	/**
	 * Computes and returns the bounds of the rectangle corresponding to a widget line range. The
	 * rectangle is in pixel coordinates relative to the text widget's
	 * {@link StyledText#getClientArea() client area} and has the width of the ruler.
	 * 
	 * @param range the widget line range
	 * @return the box bounds corresponding to <code>range</code>
	 */
	private Rectangle computeBoxBounds(ILineRange range) {
		int y1= fWidget.getLinePixel(range.getStartLine());
		int y2= fWidget.getLinePixel(range.getStartLine() + range.getNumberOfLines());

		return new Rectangle(0, y1, getWidth(), y2 - y1 - 1);
	}

	/**
	 * Shows (or hides) the overview annotations. Pass <code>null</code> to remove any displayed
	 * annotations.
	 * 
	 * @param revision the revision to show in the overview ruler
	 */
	private void showOverviewAnnotations(Revision revision) {
		if (fAnnotationModel == null)
			return;

		Map added= null;
		if (revision != null && fIsOverviewShowing) {
			added= new HashMap();
			for (Iterator it= revision.getRegions().iterator(); it.hasNext();) {
				ChangeRegion region= (ChangeRegion) it.next();
				for (Iterator regions= region.getAdjustedRanges().iterator(); regions.hasNext();) {
					ILineRange range= (ILineRange) regions.next();
					try {
						IRegion charRegion= toCharRegion(range);
						Position position= new Position(charRegion.getOffset(), charRegion.getLength());
						Annotation annotation= new RevisionAnnotation(revision.getId());
						added.put(annotation, position);
					} catch (BadLocationException x) {
						// ignore - document was changed, show no annotations
					}
				}
			}
		}

		if (fAnnotationModel instanceof IAnnotationModelExtension) {
			IAnnotationModelExtension ext= (IAnnotationModelExtension) fAnnotationModel;
			ext.replaceAnnotations((Annotation[]) fAnnotations.toArray(new Annotation[fAnnotations.size()]), added);
		} else {
			for (Iterator it= fAnnotations.iterator(); it.hasNext();) {
				Annotation annotation= (Annotation) it.next();
				fAnnotationModel.removeAnnotation(annotation);
			}
			if (added != null) {
				for (Iterator it= added.entrySet().iterator(); it.hasNext();) {
					Entry entry= (Entry) it.next();
					fAnnotationModel.addAnnotation((Annotation) entry.getKey(), (Position) entry.getValue());
				}
			}
		}
		fAnnotations.clear();
		if (added != null)
			fAnnotations.addAll(added.keySet());

	}

	/**
	 * Returns the character offset based region of a line range.
	 * 
	 * @param lines the line range to convert
	 * @return the character offset range corresponding to <code>range</code>
	 * @throws BadLocationException if the line range is not within the document bounds
	 */
	private IRegion toCharRegion(ILineRange lines) throws BadLocationException {
		IDocument document= fViewer.getDocument();
		int offset= document.getLineOffset(lines.getStartLine());
		int nextLine= end(lines);
		int endOffset;
		if (nextLine >= document.getNumberOfLines())
			endOffset= document.getLength();
		else
			endOffset= document.getLineOffset(nextLine);
		return new Region(offset, endOffset - offset);
	}
	
	/**
	 * Handles the selection of a revision and informs listeners.
	 * 
     * @param revision the selected revision, <code>null</code> for none
     */
    private void handleRevisionSelected(Revision revision) {
    	fRevisionSelectionProvider.revisionSelected(revision);
    }
    
    /**
     * Returns the selection provider.
     * 
     * @return the selection provider
     */
    public RevisionSelectionProvider getRevisionSelectionProvider() {
		return fRevisionSelectionProvider;
    }

	/**
	 * Updates the focus line with a new line.
	 * 
	 * @param line the new focus line, -1 for no focus
	 */
	private void updateFocusLine(int line) {
		if (fFocusLine != line)
			onFocusLineChanged(fFocusLine, line);
	}

	/**
	 * Handles a changing focus line.
	 * 
	 * @param previousLine the old focus line (-1 for no focus)
	 * @param nextLine the new focus line (-1 for no focus)
	 */
	private void onFocusLineChanged(int previousLine, int nextLine) {
		if (DEBUG)
			System.out.println("line: " + previousLine + " > " + nextLine); //$NON-NLS-1$ //$NON-NLS-2$
		fFocusLine= nextLine;
		ChangeRegion region= getChangeRegion(nextLine);
		updateFocusRegion(region);
	}

	/**
	 * Updates the focus region.
	 * 
	 * @param region the new focus region, <code>null</code> for no focus
	 */
	private void updateFocusRegion(ChangeRegion region) {
		if (region != fFocusRegion)
			onFocusRegionChanged(fFocusRegion, region);
	}

	/**
	 * Handles a changing focus region.
	 * 
	 * @param previousRegion the old focus region (<code>null</code> for no focus)
	 * @param nextRegion the new focus region (<code>null</code> for no focus)
	 */
	private void onFocusRegionChanged(ChangeRegion previousRegion, ChangeRegion nextRegion) {
		if (DEBUG)
			System.out.println("region: " + previousRegion + " > " + nextRegion); //$NON-NLS-1$ //$NON-NLS-2$
		fFocusRegion= nextRegion;
		Revision revision= nextRegion == null ? null : nextRegion.getRevision();
		updateFocusRevision(revision);
	}

	private void updateFocusRevision(Revision revision) {
	    if (fFocusRevision != revision)
			onFocusRevisionChanged(fFocusRevision, revision);
    }

	/**
	 * Handles a changing focus revision.
	 * 
	 * @param previousRevision the old focus revision (<code>null</code> for no focus)
	 * @param nextRevision the new focus revision (<code>null</code> for no focus)
	 */
	private void onFocusRevisionChanged(Revision previousRevision, Revision nextRevision) {
		if (DEBUG)
			System.out.println("revision: " + previousRevision + " > " + nextRevision); //$NON-NLS-1$ //$NON-NLS-2$
		fFocusRevision= nextRevision;
		uninstallWheelHandler();
		installWheelHandler();
		showOverviewAnnotations(fFocusRevision);
		redraw(); // pick up new highlights
	}

	/**
	 * Uninstalls the mouse wheel handler.
	 */
	private void uninstallWheelHandler() {
		fControl.removeListener(SWT.MouseWheel, fMouseHandler);
		fWheelHandlerInstalled= false;
	}

	/**
	 * Installs the mouse wheel handler.
	 */
	private void installWheelHandler() {
		if (fFocusRevision != null && !fWheelHandlerInstalled) {
			fControl.addListener(SWT.MouseWheel, fMouseHandler);
			fWheelHandlerInstalled= true;
		}
	}

	/**
	 * Handles a hover event on the focus revision.
	 */
	private void onHover() {
	}

	/**
	 * Handles a mouse enter event on the focus revision
	 */
	private void onEnter() {
		fIsOverviewShowing= true;
	}

	/**
	 * Handles a mouse exit event on the focus revision
	 */
	private void onExit() {
		fIsOverviewShowing= false;
	}

	/**
	 * Handles a mouse wheel event.
	 * 
	 * @param event the mouse wheel event
	 */
	private void handleMouseWheel(Event event) {
		boolean up= event.count > 0;
		int documentHoverLine= fFocusLine;

		ILineRange nextWidgetRange= null;
		ILineRange last= null;
		if (up) {
			outer: for (Iterator it= fFocusRevision.getRegions().iterator(); it.hasNext();) {
				ChangeRegion region= (ChangeRegion) it.next();
				for (Iterator regions= region.getAdjustedRanges().iterator(); regions.hasNext();) {
					ILineRange range= (ILineRange) regions.next();

					ILineRange widgetRange= modelLinesToWidgetLines(range);
					if (contains(range, documentHoverLine)) {
						nextWidgetRange= last;
						break outer;
					}
					if (widgetRange != null)
						last= widgetRange;
				}
			}
		} else {
			outer: for (ListIterator it= fFocusRevision.getRegions().listIterator(fFocusRevision.getRegions().size()); it.hasPrevious();) {
				ChangeRegion region= (ChangeRegion) it.previous();
				for (ListIterator regions= region.getAdjustedRanges().listIterator(region.getAdjustedRanges().size()); regions.hasPrevious();) {
					ILineRange range= (ILineRange) regions.previous();

					ILineRange widgetRange= modelLinesToWidgetLines(range);
					if (contains(range, documentHoverLine)) {
						nextWidgetRange= last;
						break outer;
					}
					if (widgetRange != null)
						last= widgetRange;
				}
			}
		}

		if (nextWidgetRange == null)
			return;

		int widgetCurrentFocusLine= modelLinesToWidgetLines(new LineRange(documentHoverLine, 1)).getStartLine();
		int widgetNextFocusLine= nextWidgetRange.getStartLine();
		int newTopPixel= fWidget.getTopPixel() + JFaceTextUtil.computeLineHeight(fWidget, widgetCurrentFocusLine, widgetNextFocusLine, widgetNextFocusLine - widgetCurrentFocusLine);
		fWidget.setTopPixel(newTopPixel);
		if (newTopPixel < 0) {
			Point cursorLocation= fWidget.getDisplay().getCursorLocation();
			cursorLocation.y+= newTopPixel;
			fWidget.getDisplay().setCursorLocation(cursorLocation);
		} else {
			int topPixel= fWidget.getTopPixel();
			if (topPixel < newTopPixel) {
				Point cursorLocation= fWidget.getDisplay().getCursorLocation();
				cursorLocation.y+= newTopPixel - topPixel;
				fWidget.getDisplay().setCursorLocation(cursorLocation);
			}
		}
		updateFocusLine(toDocumentLineNumber(fWidget.toControl(fWidget.getDisplay().getCursorLocation()).y));
		immediateUpdate();
	}

	/**
	 * Triggers a redraw in the display thread.
	 */
	private final void postRedraw() {
		if (isConnected() && !fControl.isDisposed()) {
			Display d= fControl.getDisplay();
			if (d != null) {
				d.asyncExec(new Runnable() {
					public void run() {
						redraw();
					}
				});
			}
		}
	}

	/**
	 * Translates a y coordinate in the pixel coordinates of the column's control to a document line
	 * number.
	 * 
	 * @param y the y coordinate
	 * @return the corresponding document line, -1 for no line
	 * @see CompositeRuler#toDocumentLineNumber(int)
	 */
	private int toDocumentLineNumber(int y) {
		return fParentRuler.toDocumentLineNumber(y);
	}

	/**
	 * Triggers redrawing of the column.
	 */
	private void redraw() {
		fColumn.redraw();
	}

	/**
	 * Triggers immediate redrawing of the entire column - use with care.
	 */
	private void immediateUpdate() {
		fParentRuler.immediateUpdate();
	}

	/**
	 * Returns the width of the column.
	 * 
	 * @return the width of the column
	 */
	private int getWidth() {
		return fColumn.getWidth();
	}

	/**
	 * Returns the System background color for list widgets.
	 * 
	 * @return the System background color for list widgets
	 */
	private Color getBackground() {
		if (fBackground == null)
			return fWidget.getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND);
		return fBackground;
	}

	/**
	 * Sets the hover later returned by {@link #getHover()}.
	 * 
	 * @param hover the hover
	 */
	public void setHover(IAnnotationHover hover) {
		// TODO ignore for now - must make revision hover settable from outside
	}

	/**
	 * Returns <code>true</code> if the receiver can provide a hover for a certain document line.
	 * 
	 * @param activeLine the document line of interest
	 * @return <code>true</code> if the receiver can provide a hover
	 */
	public boolean hasHover(int activeLine) {
		return fViewer instanceof ISourceViewer && fHover.getHoverLineRange((ISourceViewer) fViewer, activeLine) != null;
	}

	/**
	 * Returns the revision at a certain document offset, or <code>null</code> for none.
	 * 
	 * @param offset the document offset
	 * @return the revision at offset, or <code>null</code> for none
	 */
    Revision getRevision(int offset) {
    	IDocument document= fViewer.getDocument();
    	int line;
        try {
	        line= document.getLineOfOffset(offset);
        } catch (BadLocationException x) {
        	return null;
        }
    	if (line != -1) {
    		ChangeRegion region= getChangeRegion(line);
    		if (region != null)
    			return region.getRevision();
    	}
    	return null;
    }

	/**
	 * Returns <code>true</code> if a revision model has been set, <code>false</code> otherwise.
	 * 
     * @return <code>true</code> if a revision model has been set, <code>false</code> otherwise
     */
    public boolean hasInformation() {
	    return fRevisionInfo != null;
    }

	/**
	 * Returns the width in chars required to display information.
	 * 
	 * @return the width in chars required to display information
	 * @since 3.3
	 */
	public int getRequiredWidth() {
		if (fRequiredWidth == -1) {
			if (hasInformation() && (fShowRevision || fShowAuthor)) {
				int revisionWidth= 0;
				int authorWidth= 0;
				for (Iterator it= fRevisionInfo.getRevisions().iterator(); it.hasNext();) {
					Revision revision= (Revision) it.next();
					revisionWidth= Math.max(revisionWidth, revision.getId().length());
					authorWidth= Math.max(authorWidth, revision.getAuthor().length());
				}
				fRevisionIdChars= revisionWidth + 1;
				if (fShowAuthor && fShowRevision)
					fRequiredWidth= revisionWidth + authorWidth + 2;
				else if (fShowAuthor)
					fRequiredWidth= authorWidth + 1;
				else
					fRequiredWidth= revisionWidth + 1;
			} else {
				fRequiredWidth= 0;
			}
		}
		return fRequiredWidth;
	}

	/**
	 * Enables showing the revision id.
	 *  
	 * @param show <code>true</code> to show the revision, <code>false</code> to hide it
	 */
	public void showRevisionId(boolean show) {
		if (fShowRevision != show) {
			fRequiredWidth= -1;
			fRevisionIdChars= 0;
			fShowRevision= show;
			postRedraw();
		}
	}
	
	/**
	 * Enables showing the revision author.
	 *  
	 * @param show <code>true</code> to show the author, <code>false</code> to hide it
	 */
	public void showRevisionAuthor(boolean show) {
		if (fShowAuthor != show) {
			fRequiredWidth= -1;
			fRevisionIdChars= 0;
			fShowAuthor= show;
			postRedraw();
		}
	}
}