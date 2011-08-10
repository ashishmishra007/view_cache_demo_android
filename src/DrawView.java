package nz.gen.geek_central.view_cache_demo;
/*
    Demonstration of how to do smooth scrolling of a complex
    image by careful caching of bitmaps--actual view caching.

    Copyright 2011 by Lawrence D'Oliveiro <ldo@geek-central.gen.nz>.

    Licensed under the Apache License, Version 2.0 (the "License"); you may not
    use this file except in compliance with the License. You may obtain a copy of
    the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
    License for the specific language governing permissions and limitations under
    the License.
*/

import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Bitmap;
import android.view.MotionEvent;

public class DrawView extends android.view.View
  {
    protected android.content.Context Context;
    protected float ZoomFactor;
    protected float ScrollX, ScrollY; /* [0.0 .. 1.0] */
    protected final float MaxZoomFactor = 32.0f;
    protected final float MinZoomFactor = 1.0f;

    protected Drawer DrawWhat;
    protected boolean UseCaching = true;

    protected static class ViewCacheBits
      {
        public final Bitmap Bits;
        public final RectF Bounds;

        public ViewCacheBits
          (
            Bitmap Bits,
            RectF Bounds
          )
          {
            this.Bits = Bits;
            this.Bounds = Bounds;
          } /*ViewCacheBits*/

      } /*ViewCacheBits*/

    protected PointF
        LastMouse1 = null,
        LastMouse2 = null;
    protected int
        Mouse1ID = -1,
        Mouse2ID = -1;
    protected final float MaxCacheFactor = 2.0f;
      /* how far to cache beyond visible bounds */
    protected ViewCacheBits ViewCache = null;
    protected ViewCacheBuilder BuildViewCache = null;
    protected boolean CacheRebuildNeeded = false;
    boolean MouseMoved = false;

    protected class ViewCacheBuilder extends android.os.AsyncTask<Void, Integer, ViewCacheBits>
      {
        protected RectF ScaledViewBounds, CacheBounds;

        protected void onPreExecute()
          {
            final ViewParms v = new ViewParms();
            ScaledViewBounds = new RectF(0, 0, v.ScaledViewWidth, v.ScaledViewHeight);
            final PointF ViewOffset = ScrollOffset(v);
            final PointF ViewCenter = new PointF
              (
                /*x =*/ v.ViewWidth / 2.0f - ViewOffset.x,
                /*y =*/ v.ViewHeight / 2.0f - ViewOffset.y
              );
            CacheBounds = new RectF
              (
                /*left =*/
                    Math.max
                      (
                        ViewCenter.x - v.ViewWidth * MaxCacheFactor / 2.0f,
                        ScaledViewBounds.left
                      ),
                /*top =*/
                    Math.max
                      (
                        ViewCenter.y - v.ViewHeight * MaxCacheFactor / 2.0f,
                        ScaledViewBounds.top
                      ),
                /*right =*/
                    Math.min
                      (
                        ViewCenter.x + v.ViewWidth * MaxCacheFactor / 2.0f,
                        ScaledViewBounds.right
                      ),
                /*bottom =*/
                    Math.min
                      (
                        ViewCenter.y + v.ViewHeight * MaxCacheFactor / 2.0f,
                        ScaledViewBounds.bottom
                      )
              );
            if (CacheBounds.isEmpty())
              {
              /* can seem to happen, e.g. on orientation change */
                cancel(true);
              } /*if*/
          } /*onPreExecute*/

        protected ViewCacheBits doInBackground
          (
            Void... Unused
          )
          {
            final Bitmap CacheBits =
                Bitmap.createBitmap
                  (
                    /*width =*/ (int)(CacheBounds.right - CacheBounds.left),
                    /*height =*/ (int)(CacheBounds.bottom - CacheBounds.top),
                    /*config =*/ Bitmap.Config.ARGB_8888
                  );
            final android.graphics.Canvas CacheDraw = new android.graphics.Canvas(CacheBits);
            final RectF DestRect = new RectF(ScaledViewBounds);
            DestRect.offset(- CacheBounds.left, - CacheBounds.top);
            DrawWhat.Draw(CacheDraw, DestRect); /* this is the time-consuming part */
            CacheBits.prepareToDraw();
            return
                new ViewCacheBits(CacheBits, CacheBounds);
          } /*doInBackground*/

        protected void onCancelled
          (
            ViewCacheBits Result
          )
          {
            Result.Bits.recycle();
          } /*onCancelled*/

        protected void onPostExecute
          (
            ViewCacheBits Result
          )
          {
            DisposeViewCache();
            ViewCache = Result;
            BuildViewCache = null;
            CacheRebuildNeeded = false;
          } /*onPostExecute*/

      } /*ViewCacheBuilder*/

    protected void Init
      (
        android.content.Context Context
      )
      /* common code for all constructors */
      {
        this.Context = Context;
        ZoomFactor = 1.0f;
        ScrollX = 0.5f;
        ScrollY = 0.5f;
        setHorizontalFadingEdgeEnabled(true);
        setVerticalFadingEdgeEnabled(true);
      } /*Init*/

    public DrawView
      (
        android.content.Context Context
      )
      {
        super(Context);
        Init(Context);
      } /*DrawView*/

    public DrawView
      (
        android.content.Context Context,
        android.util.AttributeSet Attributes
      )
      {
        this(Context, Attributes, 0);
      } /*DrawView*/

    public DrawView
      (
        android.content.Context Context,
        android.util.AttributeSet Attributes,
        int DefaultStyle
      )
      {
        super(Context, Attributes, DefaultStyle);
        Init(Context);
      } /*DrawView*/

  /*
    Implementation of saving/restoring instance state. Doing this
    allows me to transparently restore scroll/zoom state if system
    needs to kill me while I'm in the background, or on an orientation
    change while I'm in the foreground.

    Notes: View.onSaveInstanceState returns AbsSavedState.EMPTY_STATE,
    and View.onRestoreInstanceState expects to be passed this. Also,
    both superclass methods MUST be called in my overrides (the docs
    don't make this clear).
  */

    protected static class SavedDrawViewState extends android.view.AbsSavedState
      {
        public static android.os.Parcelable.Creator<SavedDrawViewState> CREATOR =
            new android.os.Parcelable.Creator<SavedDrawViewState>()
              {
                public SavedDrawViewState createFromParcel
                  (
                    android.os.Parcel SavedState
                  )
                  {
                    System.err.println("DrawView.SavedDrawViewState.createFromParcel"); /* debug */
                    final android.view.AbsSavedState SuperState =
                        android.view.AbsSavedState.CREATOR.createFromParcel(SavedState);
                    final android.os.Bundle MyState = SavedState.readBundle();
                    return
                        new SavedDrawViewState
                          (
                            SuperState,
                            MyState.getFloat("ScrollX", 0.5f),
                            MyState.getFloat("ScrollY", 0.5f),
                            MyState.getFloat("ZoomFactor", 1.0f)
                          );
                  } /*createFromParcel*/

                public SavedDrawViewState[] newArray
                  (
                    int NrElts
                  )
                  {
                    return
                        new SavedDrawViewState[NrElts];
                  } /*newArray*/
              } /*Parcelable.Creator*/;

        public final android.os.Parcelable SuperState;
      /* state that I'm actually interested in saving/restoring: */
        public final float ScrollX, ScrollY, ZoomFactor;

        public SavedDrawViewState
          (
            android.os.Parcelable SuperState,
            float ScrollX,
            float ScrollY,
            float ZoomFactor
          )
          {
            super(SuperState);
            this.SuperState = SuperState;
            this.ScrollX = ScrollX;
            this.ScrollY = ScrollY;
            this.ZoomFactor = ZoomFactor;
          } /*SavedDrawViewState*/

        public void writeToParcel
          (
            android.os.Parcel SavedState,
            int Flags
          )
          {
            System.err.println("DrawView.SavedDrawViewState.writeToParcel"); /* debug */
            super.writeToParcel(SavedState, Flags);
          /* put my state in a Bundle, where each item is associated with a
            keyword name (unlike the Parcel itself, where items are identified
            by order). I think this makes things easier to understand. */
            final android.os.Bundle MyState = new android.os.Bundle();
            MyState.putFloat("ScrollX", ScrollX);
            MyState.putFloat("ScrollY", ScrollY);
            MyState.putFloat("ZoomFactor", ZoomFactor);
            SavedState.writeBundle(MyState);
          } /*writeToParcel*/

      } /*SavedDrawViewState*/

    @Override
    public android.os.Parcelable onSaveInstanceState()
      {
        System.err.println("DrawView called to save instance state"); /* debug */
        return
            new SavedDrawViewState
              (
                super.onSaveInstanceState(),
                ScrollX,
                ScrollY,
                ZoomFactor
              );
      } /*onSaveInstanceState*/

    @Override
    public void onRestoreInstanceState
      (
        android.os.Parcelable SavedState
      )
      {
        System.err.println("DrawView called to restore instance state " + (SavedState != null ? "non-null" : "null")); /* debug */
        final SavedDrawViewState MyState = (SavedDrawViewState)SavedState;
        super.onRestoreInstanceState(MyState.SuperState);
        ScrollX = MyState.ScrollX;
        ScrollY = MyState.ScrollY;
        ZoomFactor = MyState.ZoomFactor;
        invalidate();
      } /*onRestoreInstanceState*/

    public void SetDrawer
      (
        Drawer DrawWhat
      )
      {
        this.DrawWhat = DrawWhat;
      } /*SetDrawer*/

    public boolean GetUseCaching()
      {
        return
            UseCaching;
      } /*GetUseCaching*/

    public void SetUseCaching
      (
        boolean NewUseCaching
      )
      {
        UseCaching = NewUseCaching;
        if (!UseCaching)
          {
            ForgetViewCache();
          } /*if*/
      } /*SetUseCaching*/

    protected void DisposeViewCache()
      {
        if (ViewCache != null)
          {
            ViewCache.Bits.recycle();
            ViewCache = null;
          } /*if*/
      } /*DisposeViewCache*/

    protected void CancelViewCacheBuild()
      {
        if (BuildViewCache != null)
          {
            BuildViewCache.cancel
              (
                false
                  /* not true to allow onCancelled to recycle bitmap */
              );
            BuildViewCache = null;
          } /*if*/
      } /*CancelViewCacheBuild*/

    public void ForgetViewCache()
      {
        CancelViewCacheBuild();
        DisposeViewCache();
      } /*ForgetViewCache*/

    protected void RebuildViewCache()
      {
        CancelViewCacheBuild();
        BuildViewCache = new ViewCacheBuilder();
        BuildViewCache.execute((Void)null);
      } /*RebuildViewCache*/

    protected class ViewParms
      /* parameters for scaling and positioning map display */
      {
        public final float DrawWidth, DrawHeight;
        public final float ViewWidth, ViewHeight;
        public float ScaledViewWidth, ScaledViewHeight;

        public ViewParms
          (
            float ZoomFactor
          )
          {
            DrawWidth = DrawWhat.Bounds.right - DrawWhat.Bounds.left;
            DrawHeight = DrawWhat.Bounds.bottom - DrawWhat.Bounds.top;
            ViewWidth = getWidth();
            ViewHeight = getHeight();
            ScaledViewWidth = ViewWidth * ZoomFactor;
            ScaledViewHeight = ViewHeight * ZoomFactor;
            if (ScaledViewWidth > ScaledViewHeight * DrawWidth / DrawHeight)
              {
                ScaledViewWidth = ScaledViewHeight * DrawWidth / DrawHeight;
              }
            else if (ScaledViewHeight > ScaledViewWidth * DrawHeight / DrawWidth)
              {
                ScaledViewHeight = ScaledViewWidth * DrawHeight / DrawWidth;
              } /*if*/
          } /*ViewParms*/

        public ViewParms()
          {
            this(DrawView.this.ZoomFactor);
          } /*ViewParms*/

      } /*ViewParms*/

    protected PointF ScrollOffset
      (
        ViewParms v
      )
      /* returns the amounts by which to offset the scaled view as
        computed from the current scroll values. Note both components
        will be non-positive. */
      {
        return
            new PointF
              (
                /*x =*/
                        (v.ViewWidth - v.ScaledViewWidth)
                    *
                        (v.ScaledViewWidth >= v.ViewWidth ? ScrollX : 0.5f),
                /*y =*/
                        (v.ViewHeight - v.ScaledViewHeight)
                    *
                        (v.ScaledViewHeight >= v.ViewHeight ? ScrollY : 0.5f)
              );
      } /*ScrollOffset*/

    @Override
    public void onDraw
      (
        android.graphics.Canvas g
      )
      {
        if (DrawWhat != null)
          {
            final ViewParms v = new ViewParms();
            final PointF ViewOffset = ScrollOffset(v);
            if (ViewCache != null)
              {
              /* cache available, use it */
                final RectF DestRect = new RectF(ViewCache.Bounds);
                DestRect.offset(ViewOffset.x, ViewOffset.y);
              /* Unfortunately, the sample image doesn't look exactly the same
                when drawn offscreen and then copied on-screen, versus being
                drawn directly on-screen: path strokes are slightly thicker
                in the former case. Not sure what to do about this. */
                g.drawBitmap(ViewCache.Bits, null, DestRect, null);
              }
            else
              {
              /* do it the slow way */
                final RectF DestRect = new RectF(0, 0, v.ScaledViewWidth, v.ScaledViewHeight);
                DestRect.offset(ViewOffset.x, ViewOffset.y);
                DrawWhat.Draw(g, DestRect);
                if (UseCaching && BuildViewCache == null)
                  {
                  /* first call, nobody has called RebuildViewCache yet, do it */
                    RebuildViewCache();
                  /* Note, however, that CPU contention can slow down the cache rebuild
                    if the user does a lot of rapid scrolling in the meantime. Would
                    probably work better on multicore. */
                  } /*if*/
              } /*if*/
          } /*if*/
      } /*onDraw*/

    @Override
    public boolean onTouchEvent
      (
        MotionEvent TheEvent
      )
      {
        boolean Handled = false;
        switch (TheEvent.getAction() & (1 << MotionEvent.ACTION_POINTER_ID_SHIFT) - 1)
          {
        case MotionEvent.ACTION_DOWN:
            LastMouse1 = new PointF(TheEvent.getX(), TheEvent.getY());
            Mouse1ID = TheEvent.getPointerId(0);
            MouseMoved = false;
            Handled = true;
        break;
        case MotionEvent.ACTION_POINTER_DOWN:
              {
                final int PointerIndex =
                        (TheEvent.getAction() & MotionEvent.ACTION_POINTER_ID_MASK)
                    >>
                        MotionEvent.ACTION_POINTER_ID_SHIFT;
                final int MouseID = TheEvent.getPointerId(PointerIndex);
                System.err.println("PuzzleView: semi-down pointer ID " + MouseID); /* debug */
                final PointF MousePos = new PointF
                  (
                    TheEvent.getX(PointerIndex),
                    TheEvent.getY(PointerIndex)
                  );
                if (LastMouse1 == null)
                  {
                    Mouse1ID = MouseID;
                    LastMouse1 = MousePos;
                  }
                else if (LastMouse2 == null)
                  {
                    Mouse2ID = MouseID;
                    LastMouse2 = MousePos;
                  } /*if*/
              }
            Handled = true;
        break;
        case MotionEvent.ACTION_MOVE:
            if (LastMouse1 != null && DrawWhat != null)
              {
                final int Mouse1Index = TheEvent.findPointerIndex(Mouse1ID);
                final int Mouse2Index =
                    LastMouse2 != null ?
                        TheEvent.findPointerIndex(Mouse2ID)
                    :
                        -1;
                if (Mouse1Index >= 0 || Mouse2Index >= 0)
                  {
                    final PointF ThisMouse1 =
                        Mouse1Index >= 0 ?
                            new PointF
                              (
                                TheEvent.getX(Mouse1Index),
                                TheEvent.getY(Mouse1Index)
                              )
                        :
                            null;
                    final PointF ThisMouse2 =
                        Mouse2Index >= 0 ?
                            new PointF
                             (
                               TheEvent.getX(Mouse2Index),
                               TheEvent.getY(Mouse2Index)
                             )
                         :
                            null;
                    if (ThisMouse1 != null || ThisMouse2 != null)
                      {
                        final PointF ThisMouse =
                            ThisMouse1 != null ?
                                ThisMouse2 != null ?
                                    new PointF
                                      (
                                        (ThisMouse1.x + ThisMouse2.x) / 2.0f,
                                        (ThisMouse1.y + ThisMouse2.y) / 2.0f
                                      )
                                :
                                    ThisMouse1
                            :
                                ThisMouse2;
                        final PointF LastMouse =
                            ThisMouse1 != null ?
                                ThisMouse2 != null ?
                                    new PointF
                                      (
                                        (LastMouse1.x + LastMouse2.x) / 2.0f,
                                        (LastMouse1.y + LastMouse2.y) / 2.0f
                                      )
                                :
                                    LastMouse1
                            :
                                LastMouse2;
                        final ViewParms v = new ViewParms();
                        if (v.ScaledViewWidth > v.ViewWidth && ThisMouse.x != LastMouse.x)
                          {
                            final float ScrollDelta =
                                (ThisMouse.x - LastMouse.x) / (v.ViewWidth - v.ScaledViewWidth);
                            ScrollX = Math.max(0.0f, Math.min(1.0f, ScrollX + ScrollDelta));
                            NoCacheInvalidate();
                          } /*if*/
                        if (v.ScaledViewHeight > v.ViewHeight && ThisMouse.y != LastMouse.y)
                          {
                            final float ScrollDelta =
                                (ThisMouse.y - LastMouse.y) / (v.ViewHeight - v.ScaledViewHeight);
                            ScrollY = Math.max(0.0f, Math.min(1.0f, ScrollY + ScrollDelta));
                            NoCacheInvalidate();
                          } /*if*/
                        if (Math.hypot(ThisMouse.x - LastMouse.x, ThisMouse.y - LastMouse.y) > 2.0)
                          {
                            MouseMoved = true;
                          } /*if*/
                        if (ThisMouse1 != null && ThisMouse2 != null)
                          {
                          /* pinch to zoom */
                            final float LastDistance = (float)Math.hypot
                              (
                                LastMouse1.x - LastMouse2.x,
                                LastMouse1.y - LastMouse2.y
                              );
                            final float ThisDistance = (float)Math.hypot
                              (
                                ThisMouse1.x - ThisMouse2.x,
                                ThisMouse1.y - ThisMouse2.y
                              );
                            if
                              (
                                    LastDistance != 0.0f
                                &&
                                    ThisDistance != 0.0f
                              )
                              {
                                ZoomBy(ThisDistance /  LastDistance);
                              } /*if*/
                          } /*if*/
                        LastMouse1 = ThisMouse1;
                        LastMouse2 = ThisMouse2;
                      } /*if*/
                  } /*if*/
              } /*if*/
            Handled = true;
        break;
        case MotionEvent.ACTION_POINTER_UP:
            if (LastMouse2 != null)
              {
                final int PointerIndex =
                        (TheEvent.getAction() & MotionEvent.ACTION_POINTER_ID_MASK)
                    >>
                        MotionEvent.ACTION_POINTER_ID_SHIFT;
                final int PointerID = TheEvent.getPointerId(PointerIndex);
                System.err.println("PuzzleView: semi-up pointer ID " + PointerID); /* debug */
                if (PointerID == Mouse1ID)
                  {
                    Mouse1ID = Mouse2ID;
                    LastMouse1 = LastMouse2;
                    Mouse2ID = -1;
                    LastMouse2 = null;
                  }
                else if (PointerID == Mouse2ID)
                  {
                    Mouse2ID = -1;
                    LastMouse2 = null;
                  } /*if*/
              } /*if*/
            Handled = true;
        break;
        case MotionEvent.ACTION_UP:
            if (LastMouse1 != null && !MouseMoved)
              {
              /* move point that user tapped to centre of view if possible */
                final ViewParms v = new ViewParms();
                final PointF ViewOffset = ScrollOffset(v);
                final PointF NewCenter = new PointF
                  (
                    (DrawWhat.Bounds.right + DrawWhat.Bounds.left) / 2.0f,
                    (DrawWhat.Bounds.bottom + DrawWhat.Bounds.top) / 2.0f
                  );
                final PointF LastMouse =
                    LastMouse2 != null ?
                        new PointF
                          (
                            (LastMouse1.x + LastMouse2.x) / 2.0f,
                            (LastMouse1.y + LastMouse2.y) / 2.0f
                          )
                    :
                        LastMouse1;
                if (v.ScaledViewWidth > v.ViewWidth)
                  {
                    NewCenter.x =
                                (LastMouse.x - ViewOffset.x)
                           /
                                v.ScaledViewWidth
                           *
                                v.DrawWidth
                        +
                            DrawWhat.Bounds.left;
                  } /*if*/
                if (v.ScaledViewHeight > v.ViewHeight)
                  {
                    NewCenter.y =
                                (LastMouse.y - ViewOffset.y)
                           /
                                v.ScaledViewHeight
                           *
                                v.DrawHeight
                        +
                            DrawWhat.Bounds.top;
                  } /*if*/
                ScrollTo(NewCenter.x, NewCenter.y);
              } /*if*/
            LastMouse1 = null;
            LastMouse2 = null;
            Mouse1ID = -1;
            Mouse2ID = -1;
            if (CacheRebuildNeeded && BuildViewCache == null)
              {
                invalidate();
              } /*if*/
            Handled = true;
        break;
          } /*switch*/
        return
            Handled;
      } /*onTouchEvent*/

    public void ZoomBy
      (
        float Factor
      )
      /* multiplies the current zoom by Factor. */
      {
        final float NewZoomFactor =
            Math.min
              (
                Math.max
                  (
                    ZoomFactor * Math.abs(Factor),
                    MinZoomFactor
                  ),
                MaxZoomFactor
              );
        if (NewZoomFactor != ZoomFactor)
          {
            DisposeViewCache();
          /* try to adjust scroll offset so point in map at centre of view stays in centre */
            final ViewParms v1 = new ViewParms();
            final ViewParms v2 = new ViewParms(NewZoomFactor);
            if (v1.ScaledViewWidth > v1.ViewWidth && v2.ScaledViewWidth > v2.ViewWidth)
              {
                ScrollX =
                        (
                                (
                                    v1.ViewWidth / 2.0f
                                +
                                    ScrollX * (v1.ScaledViewWidth - v1.ViewWidth)
                                )
                            /
                                v1.ScaledViewWidth
                            *
                                v2.ScaledViewWidth
                        -
                            v2.ViewWidth / 2.0f
                        )
                    /
                        (v2.ScaledViewWidth - v2.ViewWidth);
              } /*if*/
            if (v1.ScaledViewHeight > v1.ViewHeight && v2.ScaledViewHeight > v2.ViewHeight)
              {
                ScrollY =
                        (
                                (
                                    v1.ViewHeight / 2.0f
                                +
                                    ScrollY * (v1.ScaledViewHeight - v1.ViewHeight)
                                )
                            /
                                v1.ScaledViewHeight
                            *
                                v2.ScaledViewHeight
                        -
                            v2.ViewHeight / 2.0f
                        )
                    /
                        (v2.ScaledViewHeight - v2.ViewHeight);
              } /*if*/
            ZoomFactor = NewZoomFactor;
            invalidate();
          } /*if*/
      } /*ZoomBy*/

    public void ScrollTo
      (
        float X,
        float Y
      )
      /* tries to ensure the specified position is at the centre of the view. */
      {
        if (DrawWhat != null)
          {
            final ViewParms v = new ViewParms();
            final float OldScrollX = ScrollX;
            final float OldScrollY = ScrollY;
            if (v.ScaledViewWidth > v.ViewWidth)
              {
                ScrollX =
                        (
                            (X - DrawWhat.Bounds.left) / v.DrawWidth * v.ScaledViewWidth
                        -
                            v.ViewWidth / 2.0f
                        )
                    /
                        (v.ScaledViewWidth - v.ViewWidth);
                ScrollX = Math.max(0.0f, Math.min(1.0f, ScrollX));
              } /*if*/
            if (v.ScaledViewHeight > v.ViewHeight)
              {
                ScrollY =
                        (
                            (Y - DrawWhat.Bounds.top) / v.DrawHeight * v.ScaledViewHeight
                        -
                            v.ViewHeight / 2.0f
                        )
                    /
                        (v.ScaledViewHeight - v.ViewHeight);
                ScrollY = Math.max(0.0f, Math.min(1.0f, ScrollY));
              } /*if*/
            if (OldScrollX != ScrollX || OldScrollY != ScrollY)
              {
                invalidate();
              } /*if*/
          } /*if*/
      } /*ScrollTo*/

    protected void NoCacheInvalidate()
      /* try to avoid “java.lang.OutOfMemoryError: bitmap size exceeds VM budget”
        crashes by minimizing cache rebuild calls. */
      {
        CacheRebuildNeeded = UseCaching;
        super.invalidate();
      } /*NoCacheInvalidate*/

    @Override
    public void invalidate()
      {
        if (DrawWhat != null)
          {
            DisposeViewCache();
              /* because redraw might happen before cache generation is complete */
            if (UseCaching)
              {
                RebuildViewCache();
              } /*if*/
          } /*if*/
        super.invalidate();
      } /*invalidate*/

  /* implementing the following (and calling setxxxFadingEdgeEnabled(true)
    in the constructors, above) will cause fading edges to appear */

    protected static final int ScrollScale = 1000;

    @Override
    protected int computeHorizontalScrollExtent()
      {
        final ViewParms v = new ViewParms();
        return
            (int)Math.round(v.ViewWidth * ScrollScale / v.ScaledViewWidth);
      } /*computeHorizontalScrollExtent*/

    @Override
    protected int computeHorizontalScrollOffset()
      {
        final ViewParms v = new ViewParms();
        return
            v.ScaledViewWidth > v.ViewWidth ?
                (int)Math.round(ScrollX * ScrollScale * (v.ScaledViewWidth - v.ViewWidth) /  v.ScaledViewWidth)
            :
                0;
      } /*computeHorizontalScrollOffset*/

    @Override
    protected int computeHorizontalScrollRange()
      {
        return
            ScrollScale;
      } /*computeHorizontalScrollRange*/

    @Override
    protected int computeVerticalScrollExtent()
      {
        final ViewParms v = new ViewParms();
        return
            (int)Math.round(v.ViewHeight * ScrollScale / v.ScaledViewHeight);
      } /*computeVerticalScrollExtent*/

    @Override
    protected int computeVerticalScrollOffset()
      {
        final ViewParms v = new ViewParms();
        return
            v.ScaledViewHeight > v.ViewHeight ?
                (int)Math.round(ScrollY * ScrollScale * (v.ScaledViewHeight - v.ViewHeight) / v.ScaledViewHeight)
            :
                0;
      } /*computeVerticalScrollOffset*/

    @Override
    protected int computeVerticalScrollRange()
      {
        return
            ScrollScale;
      } /*computeVerticalScrollRange*/

  } /*DrawView*/
