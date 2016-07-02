/**
MIT License

Copyright (c) 2016 Shaun Reich <sreich02@gmail.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
 */

package com.ore.infinium

import com.artemis.ComponentMapper
import com.artemis.annotations.Wire
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.scenes.scene2d.*
import com.badlogic.gdx.scenes.scene2d.ui.Image
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.badlogic.gdx.scenes.scene2d.ui.Tooltip
import com.badlogic.gdx.scenes.scene2d.utils.DragAndDrop
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable
import com.badlogic.gdx.utils.Scaling
import com.kotcrab.vis.ui.VisUI
import com.kotcrab.vis.ui.widget.Tooltip.TooltipStyle
import com.kotcrab.vis.ui.widget.VisImage
import com.kotcrab.vis.ui.widget.VisLabel
import com.kotcrab.vis.ui.widget.VisTable
import com.kotcrab.vis.ui.widget.VisWindow
import com.ore.infinium.components.BlockComponent
import com.ore.infinium.components.ItemComponent
import com.ore.infinium.components.SpriteComponent
import com.ore.infinium.systems.client.ClientNetworkSystem
import com.ore.infinium.systems.client.TileRenderSystem
import com.ore.infinium.util.getNullable

@Wire
class InventoryView(stage: Stage,
        //the hotbar inventory, for drag and drop
                    private val m_hotbarInventory: Inventory,
        //the model for this view
                    private val m_inventory: Inventory,
                    dragAndDrop: DragAndDrop,
                    private val m_world: OreWorld) : Inventory.SlotListener {

    var inventoryVisible: Boolean
        get() = m_window.isVisible
        set(value) {
            m_window.isVisible = value
        }

    private lateinit var clientNetworkSystem: ClientNetworkSystem
    private lateinit var tileRenderSystem: TileRenderSystem

    private lateinit var itemMapper: ComponentMapper<ItemComponent>
    private lateinit var blockMapper: ComponentMapper<BlockComponent>
    private lateinit var spriteMapper: ComponentMapper<SpriteComponent>

    private val m_slots = mutableListOf<SlotElement>()
    private val m_window: VisWindow

    private val m_tooltip: Tooltip<VisTable>
    private val m_tooltipLabel: VisLabel

    init {
        //attach to the inventory model
        m_inventory.addListener(this)

        val container = VisTable()
        container.setFillParent(true)
        container.center() //top().right().setSize(800, 100);
        container.defaults().space(4f)
        container.padLeft(10f).padTop(10f)

        m_window = VisWindow("Inventory")
        //fixme;not centering or anythign, all hardcoded :(
        m_window.setPosition(900f, 100f)
        m_window.top().right().setSize(400f, 500f)
        //        window.defaults().space(4);
        //window.pack();
        m_window.add(container).fill().expand()

        val region = m_world.m_artemisWorld.getSystem(TileRenderSystem::class.java).m_tilesAtlas.findRegion("dirt-00")
        val dragImage = VisImage(region)
        dragImage.setSize(32f, 32f)

        val slotsPerRow = 5
        var i = 0

        while (i < Inventory.maxSlots) {
            var slot = 0
            while (slot < slotsPerRow && i < Inventory.maxSlots) {
                val slotImage = VisImage()

                val slotTable = VisTable()
                slotTable.touchable = Touchable.enabled

                slotTable.add(slotImage)
                slotTable.addListener(SlotInputListener(this, i))
                slotTable.background("default-pane")

                slotTable.row()

                val itemName = VisLabel()
                slotTable.add(itemName).bottom().fill()

                val element = SlotElement(itemImage = slotImage, table = slotTable, itemCountLabel = itemName)
                m_slots.add(i, element)

                container.add(slotTable).size(50f, 50f)
                //            window.add(slotTable).fill().size(50, 50);

                dragAndDrop.addSource(InventoryDragSource(slotTable, i, dragImage, this))

                dragAndDrop.addTarget(InventoryDragTarget(slotTable, i, this))
                ++slot
                ++i
            }

            container.row()
        }

        val style = VisUI.getSkin().get("default", TooltipStyle::class.java)

        m_tooltipLabel = VisLabel()
        val tooltipTable = VisTable().apply {
            add(m_tooltipLabel)
            background = style.background
        }

        m_tooltip = Tooltip<VisTable>(tooltipTable)

        stage.addActor(m_window)

        inventoryVisible = false
    }

    private fun setSlotVisible(index: Int, visible: Boolean) {
        m_slots[index].itemCountLabel.isVisible = visible
        m_slots[index].itemImage.isVisible = visible
    }

    override fun countChanged(index: Int, inventory: Inventory) {
        val itemEntity = inventory.itemEntity(index)!!
        val itemComponent = itemMapper.get(itemEntity)
        m_slots[index].itemCountLabel.setText(itemComponent.stackSize.toString())
    }

    override operator fun set(index: Int, inventory: Inventory) {
        val slot = m_slots[index]

        val itemEntity = inventory.itemEntity(index)!!
        val itemComponent = itemMapper.get(itemEntity)
        m_slots[index].itemCountLabel.setText(itemComponent.stackSize.toString())

        val spriteComponent = spriteMapper.get(itemEntity)

        val region = textureForInventoryItem(itemEntity, spriteComponent.textureName!!)

        val slotImage = slot.itemImage
        slotImage.drawable = TextureRegionDrawable(region)
        slotImage.setSize(region.regionWidth.toFloat(), region.regionHeight.toFloat())
        slotImage.setScaling(Scaling.fit)

        //do not exceed the max size/resort to horrible upscaling. prefer native size of each inventory sprite.
        //.maxSize(region.getRegionWidth(), region.getRegionHeight()).expand().center();
    }

    fun textureForInventoryItem(itemEntity: Int, textureName: String): TextureRegion {
        val region: TextureRegion?
        if (blockMapper.getNullable(itemEntity) != null) {
            //fixme this concat is pretty...iffy
            region = m_world.m_artemisWorld.getSystem(TileRenderSystem::class.java).m_tilesAtlas.findRegion(
                    "$textureName-00")
        } else {
            region = m_world.m_atlas.findRegion(textureName)
        }

        assert(region != null) { "textureregion for inventory item entity id: $itemEntity, was not found!" }

        return region!!
    }


    override fun removed(index: Int, inventory: Inventory) {
        val slot = m_slots[index]
        slot.itemImage.drawable = null
        slot.itemCountLabel.setText(null)
    }

    private class SlotInputListener internal constructor(private val inventory: InventoryView, private val index: Int) : InputListener() {
        override fun enter(event: InputEvent?, x: Float, y: Float, pointer: Int, fromActor: Actor?) {
            inventory.m_tooltip.enter(event, x, y, pointer, fromActor)

            super.enter(event, x, y, pointer, fromActor)
        }

        override fun mouseMoved(event: InputEvent?, x: Float, y: Float): Boolean {
            inventory.m_tooltip.mouseMoved(event, x, y)

            val itemEntity = inventory.m_inventory.itemEntity(index)

            if (itemEntity != null) {
                val itemComponent = inventory.itemMapper.get(itemEntity)
                val spriteComponent = inventory.spriteMapper.get(itemEntity)
                inventory.m_tooltipLabel.setText(itemComponent.name)
            }

            return super.mouseMoved(event, x, y)
        }

        override fun exit(event: InputEvent?, x: Float, y: Float, pointer: Int, toActor: Actor?) {
            inventory.m_tooltip.exit(event, x, y, pointer, toActor)

            super.exit(event, x, y, pointer, toActor)
        }
    }

    private class InventoryDragSource(slotTable: Table, private val index: Int, private val dragImage: Image, private val inventoryView: InventoryView) : DragAndDrop.Source(
            slotTable) {

        override fun dragStart(event: InputEvent, x: Float, y: Float, pointer: Int): DragAndDrop.Payload? {
            //invalid drag start, ignore.
            if (inventoryView.m_inventory.itemEntity(index) == null) {
                return null
            }

            val payload = DragAndDrop.Payload()

            val dragWrapper = InventorySlotDragWrapper(type = Inventory.InventoryType.Inventory,
                                                       dragSourceIndex = index)
            payload.`object` = dragWrapper

            payload.dragActor = dragImage
            payload.validDragActor = dragImage
            payload.invalidDragActor = dragImage

            return payload
        }
    }

    private class InventoryDragTarget(slotTable: Table, private val index: Int, private val inventory: InventoryView) : DragAndDrop.Target(
            slotTable) {

        override fun drag(source: DragAndDrop.Source,
                          payload: DragAndDrop.Payload?,
                          x: Float,
                          y: Float,
                          pointer: Int): Boolean {
            if (payload == null) {
                return false
            }

            if (isValidDrop(payload)) {
                actor.color = Color.GREEN
                payload.dragActor.setColor(0f, 1f, 0f, 1f)

                return true
            } else {
                actor.color = Color.RED
                payload.dragActor.setColor(1f, 0f, 0f, 1f)
            }

            return false
        }

        private fun isValidDrop(payload: DragAndDrop.Payload): Boolean {

            val dragWrapper = payload.`object` as InventorySlotDragWrapper
            if (dragWrapper.dragSourceIndex != index) {
                //maybe make it green? the source/dest is not the same

                //only make it green if the slot is empty
                inventory.m_inventory.itemEntity(index) ?: return true
            }

            return false
        }

        override fun reset(source: DragAndDrop.Source?, payload: DragAndDrop.Payload?) {
            payload ?: error("error, payload invalid")

            payload.dragActor.setColor(1f, 1f, 1f, 1f)
            actor.color = Color.WHITE
        }

        override fun drop(source: DragAndDrop.Source, payload: DragAndDrop.Payload, x: Float, y: Float, pointer: Int) {

            val dragWrapper = payload.`object` as InventorySlotDragWrapper

            //ensure the dest is empty before attempting any drag & drop!
            if (inventory.m_inventory.itemEntity(this.index) == null) {
                if (dragWrapper.type == Inventory.InventoryType.Inventory) {
                    val itemEntity = inventory.m_inventory.itemEntity(dragWrapper.dragSourceIndex)
                    //move the item from the source to the dest (from main inventory to main inventory)
                    inventory.m_inventory.setSlot(this.index, itemEntity!!)

                    inventory.clientNetworkSystem.sendInventoryMove(Inventory.InventoryType.Inventory,
                                                                    dragWrapper.dragSourceIndex,
                                                                    Inventory.InventoryType.Inventory,
                                                                    index)

                    //remove the source item
                    inventory.m_inventory.takeItem(dragWrapper.dragSourceIndex)
                } else {
                    //hotbar inventory
                    val hotbarInventory = inventory.m_hotbarInventory

                    val itemEntity = hotbarInventory.itemEntity(dragWrapper.dragSourceIndex)
                    //move the item from the source to the dest (from hotbar inventory to this main inventory)

                    inventory.m_inventory.setSlot(this.index, itemEntity!!)

                    inventory.clientNetworkSystem.sendInventoryMove(Inventory.InventoryType.Hotbar,
                                                                    dragWrapper.dragSourceIndex,
                                                                    Inventory.InventoryType.Inventory, index)

                    //remove the source item
                    hotbarInventory.takeItem(dragWrapper.dragSourceIndex)
                }
            }

        }
    }

    private inner class SlotElement(var itemImage: VisImage, var itemCountLabel: VisLabel, var table: VisTable) {
    }

    override fun selected(index: Int, inventory: Inventory) {

    }
}
