/*
 * Arrow-drawing helpers for the Lof Quest Journal.
 *
 * Adapted from the RuneLite Quest Helper plugin's DirectionArrow/QuestPerspective
 * (https://github.com/Zoinkwiz/quest-helper), used under its BSD 2-Clause license:
 *
 * Copyright (c) 2021, Zoinkwiz <https://github.com/Zoinkwiz>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.lofquests;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;

final class LofArrow
{
	/** The big scene arrow, tip landing on (tipX, tipY). */
	static void drawWorldArrow(Graphics2D graphics, Color color, int tipX, int tipY)
	{
		Line2D.Double line = new Line2D.Double(tipX, tipY - 13, tipX, tipY);
		drawArrow(graphics, line, color, 9, 4, 5);
	}

	/** The small minimap arrow along [line] (tail → tip). */
	static void drawMinimapArrow(Graphics2D graphics, Line2D.Double line, Color color)
	{
		drawArrow(graphics, line, color, 6, 2, 2);
	}

	private static void drawArrow(Graphics2D graphics, Line2D.Double line, Color color, int width, int tipHeight, int tipWidth)
	{
		graphics.setColor(Color.BLACK);
		graphics.setStroke(new BasicStroke(width));
		graphics.draw(line);
		drawArrowHead(graphics, line, tipHeight, tipWidth);

		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(width - 3));
		graphics.draw(line);
		drawArrowHead(graphics, line, tipHeight - 2, tipWidth - 2);
		graphics.setStroke(new BasicStroke(1));
	}

	private static void drawArrowHead(Graphics2D g2d, Line2D.Double line, int extraSizeHeight, int extraSizeWidth)
	{
		Polygon arrowHead = new Polygon();
		arrowHead.addPoint(0, 6 + extraSizeHeight);
		arrowHead.addPoint(-6 - extraSizeWidth, -1 - extraSizeHeight);
		arrowHead.addPoint(6 + extraSizeWidth, -1 - extraSizeHeight);

		AffineTransform tx = new AffineTransform();
		tx.setToIdentity();
		double angle = Math.atan2(line.y2 - line.y1, line.x2 - line.x1);
		tx.translate(line.x2, line.y2);
		tx.rotate(angle - Math.PI / 2d);

		Graphics2D g = (Graphics2D) g2d.create();
		g.setTransform(tx);
		g.fill(arrowHead);
		g.dispose();
	}

	/**
	 * A point on the minimap in the direction of [destination] from the player, rotated to the
	 * minimap's current orientation — used to aim the edge arrow when the target is off-map.
	 */
	static Point getMinimapDirectionPoint(Client client, WorldPoint start, WorldPoint destination)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		Point playerOnMinimap = player.getMinimapLocation();
		if (playerOnMinimap == null)
		{
			return null;
		}

		int x = destination.getX() - start.getX();
		int y = destination.getY() - start.getY();
		float maxDistance = Math.max(Math.abs(x), Math.abs(y));
		if (maxDistance == 0)
		{
			return null;
		}
		// Only the DIRECTION matters (getEdgeLine re-derives the angle), so normalise to a fixed
		// magnitude and rotate exactly the way Perspective.localToMinimap does — same yaw source,
		// same table, same sign — so the rim arrow lines up with the real minimap orientation.
		x = (int) (x * 100 / maxDistance);
		y = (int) (y * 100 / maxDistance);

		final int angle = client.getCameraYawTarget() & 0x7FF;
		final int sin = Perspective.SINE[angle];
		final int cos = Perspective.COSINE[angle];
		final int rx = cos * x + sin * y >> 16;
		final int ry = sin * x - cos * y >> 16;

		return new Point(playerOnMinimap.getX() + rx, playerOnMinimap.getY() + ry);
	}

	/** The edge-arrow segment: from 55px to 65px out of the player dot, toward [direction]. */
	static Line2D.Double getEdgeLine(Point playerOnMinimap, Point direction)
	{
		double xDiff = playerOnMinimap.getX() - direction.getX();
		double yDiff = direction.getY() - playerOnMinimap.getY();
		double angle = Math.atan2(yDiff, xDiff);

		int startX = (int) (playerOnMinimap.getX() - (Math.cos(angle) * 55));
		int startY = (int) (playerOnMinimap.getY() + (Math.sin(angle) * 55));
		int endX = (int) (playerOnMinimap.getX() - (Math.cos(angle) * 65));
		int endY = (int) (playerOnMinimap.getY() + (Math.sin(angle) * 65));

		return new Line2D.Double(startX, startY, endX, endY);
	}

	private LofArrow()
	{
	}
}
